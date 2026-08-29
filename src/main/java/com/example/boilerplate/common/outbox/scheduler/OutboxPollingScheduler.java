package com.example.boilerplate.common.outbox.scheduler;

import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.producer.EventStreamProducer;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fallback publisher - chạy nền mỗi 10 giây để đẩy nhưng event PENDING
 * còn sót ở database vào Redis Stream
 *
 * Hỗ trợ cho OutboxEventListener khi:
 * - Redis chết tại thời đểm commit -> giữ event PENDING
 * - App crash ngay sau khi commit trước khi listener kịp chạy
 * - markQueued thất bại (lỗi DB) -> event vẫn PENDING
 *
 * Vì OutboxEventListener + OutboxPollingScheduler có thể đẩy duplicate vào stream
 * (dual‑path publish), consumer sẽ dedupe bằng check status == SENT trước khi xử lý
 * – đây là at‑least‑once.
 *
 * fixedDelay: đợt trước kết thúc mới tính chu kỳ kế tiếp → không overlap giữa các lần
 * chạy trên cùng một instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxStreamProperties outboxStreamProperties;
    private final OutboxService outboxService;
    private final EventStreamProducer eventStreamProducer;

    /**
     * Chạy nền mỗi 10 giây.
     *
     * Luồng xử lí:
     * 1. Janitor: chuyển các sự kiện QUEUED bị kẹt (không được xử lí trong hơn 15p),
     * thì đưa về trạng thái PENDING để thử đẩy lại
     * 2. Batch Fetch: lấy tối đa 100 row PENDING (có FOR UPDATE SKIP LOCKED)
     * 3. Push từng row: XADD vào Stream, thành công -> Mark QUEUED;
     * thất bại -> registerPushFailure.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:10000}")
    public void pollOutbox() {
        // Cứu row QUEUED bị kẹt (Redis FLUSHALL, MAXLEN trim, consumer group bị xóa…)
        // đưa về PENDING để chu kỳ này hoặc chu kỳ sau đẩy lại.
        // Gọi qua service vì requeueStaleQueued là @Modifying @Query cần transaction.
        outboxService.requeueStaleQueued(outboxStreamProperties.staleQueuedMinutes());

        // Batch fetch – TX chỉ sống trong câu SELECT này (vài ms).
        //    FOR UPDATE SKIP LOCKED: lock row vừa lấy, instance khác bỏ qua row đang bị lock
        //    → 2 instance chạy song song tự chia batch, không giành việc của nhau.
        //    Lock được release ngay khi method này return – KHÔNG giữ connection trong vòng for.
        List<Outbox> batch = outboxService.lockPendingBatch(outboxStreamProperties.batchSize());
        if (!batch.isEmpty()) {
            log.info("[POLLING] Fetched {} PENDING outbox(es) to push", batch.size());
        }

        // Lặp qua từng event outbox trong batch, sau đó đẩy vào stream
        // -> Nếu thành công: đánh dấu các event trong db thành QUEUED
        // -> Nếu thất bại:
        for(Outbox outbox : batch) {
            try {
                // Nêu push thành công vào stream, đổi status event từ pending
                // sang queued
                if (eventStreamProducer.push(outbox)) {
                    outboxService.markQueued(outbox.getId());
                    log.info("[POLLING] Pushed outbox={} eventType={} → QUEUED", outbox.getId(), outbox.getEventType());
                }
            } catch (Exception ex) {
                // push thất bại → ghi nhận lỗi + retry_count++ + backoff (hẹn giờ xa dần)
                // nếu retry_count đạt max_retries → chuyển FAILED (không thử nữa)
                outboxService.registerPushFailure(outbox.getId(), ex.getMessage());
                log.warn("[POLLING] Push FAILED outbox={}: {}", outbox.getId(), ex.getMessage());
            }
        }
    }
}
