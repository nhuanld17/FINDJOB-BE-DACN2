package com.example.boilerplate.common.outbox.event;

import com.example.boilerplate.common.outbox.producer.EventStreamProducer;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Đẩy event vào redis ngay sau khi transaction commit.
 *
 * Business service (ví dụ: register) ghi row outbox xong thì phát
 * 1 event OutboxSavedEvent. Class này nghe sự kiện đó - nhưng ko chạy ngay,
 * nó đợi transaction COMMIT thành công rồi mới chạy (AFTER_COMMIT)
 *
 * Vì sao phải đợi commit xong mới chạy -> nếu đẩy event mail ra lên stream
 * trước, trong trường hợp transaction rollback -> consumer gửi otp cho tài
 * khoản chưa bao giờ tồn tại
 *
 * Nếu redis chết luúc đẩy -> bắt lỗi, im lặng, giữ nguyên status là pending
 * -> OutboxPollingScheduler sẽ thử đẩy lại sau.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final EventStreamProducer eventStreamProducer;
    private final OutboxService outboxService;

    /**
     * Đẩy event vào Redis (XADD), thành công thì đánh dấu row thành queued
     *
     * Thứ tự: đẩy vào stream trước, sau đó đánh dấu thành queued sau.
     * Nếu crash giữa 2 bước -> event đã nằm trong redis nhưng row vẫn pending
     * -> polling scheduler sẽ đẩy lại lần nữa -> redis có 2 bản trùng -> consumer
     * check status == SENT và bỏ qua bản trùng.
     *
     * Bắt mọi exception, nhưng ko ném exp do transaction ở tầng nghiệp vụ đã kết thúc
     * , ném lỗi lúc này sẽ ko đuợc rollback.
     *
     * @Async("emailTaskExecutor"): chạy trên Virtual Thread riêng, KHÔNG nằm trong
     * transaction synchronization của request thread. Vì sao bắt buộc:
     * @TransactionalEventListener(AFTER_COMMIT) mặc định chạy đồng bộ trên thread
     * của request — lúc đó transaction vừa commit nhưng synchronization vẫn còn
     * active. Gọi outboxService.markQueued() (cần mở TX mới) từ đó sẽ bị Spring
     * "join" vào synchronization đã hoàn tất → không mở được TX → @Modifying
     * UPDATE query chạy không có transaction → TransactionRequiredException
     * "Executing an update/delete query". Chạy trên thread riêng sẽ thoát khỏi
     * synchronization đó → markQueued mở TX mới bình thường.
     */
    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaved(OutboxSavedEvent event) {
        try {
            if (eventStreamProducer.push(event.outbox())) {
                markQueuedWithRetry(event.outboxId());
            }
        } catch (Exception e) {
            // Redis lỗi -> không ném exp, giữ status pending
            log.warn("Push event vào stream sau commit thất bại, polling sẽ xử lí: {}", event.outboxId());
        }
    }

    /**
     * Đánh dấu QUEUED cho row, thử tối đa 3 lần (cách nhau 200ms -> 400ms -> 600ms)
     *
     * Tại sao phải retry: lúc này XADD đã thành công - event chắc chắn đã nằm
     * trong redis stream. Lỗi db lúc này chỉ là tạm thời (timeout, pool đầy).
     * Thử 3 lần vẫn hỏng thì bỏ: row giữ PENDING, polling scheduler sẽ đẩy lại
     * (chấp nhận trùng event trong stream, consumer sẽ tự loại)
     */
    private void markQueuedWithRetry(Long id) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // markQueued trả về true nếu đổi PENDING → QUEUED thành công;
                // trả false nghĩa là người khác đã đổi trạng thái trước rồi — thôi can thiệp nữa
                if (outboxService.markQueued(id)) {
                    log.info("Outbox {} queued ngay sau commit", id);
                }
                return;
            } catch (Exception ex) {
                log.warn("Mark QUEUED fail (lần {}/3) outbox {}: {}", attempt, id, ex.getMessage());
                try {                          // backoff ngắn, không làm chậm request đáng kể
                    Thread.sleep(attempt * 200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();   // khôi phục cờ interrupt rồi thoát
                    break;
                }
            }
        }
        log.error("Không mark QUEUED được outbox {} — polling sẽ đẩy lại (chấp nhận trùng)", id);
    }
}
