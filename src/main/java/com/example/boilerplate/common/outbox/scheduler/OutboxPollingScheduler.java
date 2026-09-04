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

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxService outboxService;
    private final OutboxStreamProperties outboxStreamProperties;
    private final EventStreamProducer eventStreamProducer;

    /**
     * Chạy nền mỗi 10 giây.
     *
     * Luồng xử lí:
     * - Chuyển các event có trạng thái QUEUED không được xử lí trong 15p về
     * trạng thái PENDING để thử đẩy lại
     * - Batch Fetch: lấy tối đa 100 event có status PENDING
     * - Push từng event vào Stream, nếu thành công thì đánh dấu là QUEUED;
     * thất bại -> registerFailure
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:10000}")
    public void pollOutbox() {
        // Chuyển các event có trạng thái QUEUED nhưng ko được xử lí trong 15p
        // về PENDING
        outboxService.requeueStaleQueued(outboxStreamProperties.staleQueuedMinutes());

        // Batch Fetch - Lấy ra các event có status PENDING
        List<Outbox> batch = outboxService.lockPendingBatch(outboxStreamProperties.batchSize());
        if (!batch.isEmpty()) {
            log.info("[POLLING] Fetched {} PENDING outbox(es) to push", batch.size());
        }

        // Lặp qua từng event trong batch, đẩy vào stream
        for(Outbox outbox : batch) {
            try {
                // Push thành công -> đổi status từ pending sang queued
                if (eventStreamProducer.push(outbox)) {
                    outboxService.markQueued(outbox.getId());
                    log.info("[POLLING] Pushed outbox={} eventType={} → QUEUED", outbox.getId(), outbox.getEventType());
                }
            } catch (Exception ex) {
                // Push thất bại -> ghi nhận lỗi + retry_count++ + backoff hẹn giờ retry xa dần)
                // nếu retry_count đạt max_retries -> chuyển FAILED (không thử nữa)
                outboxService.registerPushFailure(outbox.getId(), ex.getMessage());
                log.warn("[POLLING] Push FAILED outbox={}: {}", outbox.getId(), ex.getMessage());
            }
        }
    }
}
