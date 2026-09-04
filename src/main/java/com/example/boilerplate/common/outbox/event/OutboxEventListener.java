package com.example.boilerplate.common.outbox.event;

import com.example.boilerplate.common.outbox.producer.EventStreamProducer;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final EventStreamProducer eventStreamProducer;
    private final OutboxService outboxService;

    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaved(OutboxSavedEvent event) {
        try {
            if (eventStreamProducer.push(event.outbox())) {
                markQueuedWithRetry(event.outboxId());
            }
        } catch (Exception e) {
            // redis lỗi -> không ném exp, giữ status pending
            log.warn("Push event vào stream sau commit thất bại, polling sẽ xử lí: {}", event.outboxId());
        }
    }

    /**
     * Đánh dấu QUEUED cho row, thử tối đa 3 lần (cách nhau 200ms -> 400ms -> 600ms)
     *
     * Tại sao phải retry: lúc này XADD đã push event thành công - event chắc chắn đã nằm
     * trong redis stream. Lỗi db lúc này chỉ là tạm thời (timeout, pool đầy).
     * Thử 3 lần vẫn hỏng thì bỏ: row giữ PENDING, polling scheduler sẽ đẩy lại
     * (chấp nhận trùng event trong stream, consumer sẽ tự loại)
     */
    private void markQueuedWithRetry(Long id) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // markQueued trả về true nếu đổi PENDING → QUEUED thành công;
                // trả false nghĩa là người khác đã đổi trạng thái trước rồi - thôi can thiệp nữa
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
