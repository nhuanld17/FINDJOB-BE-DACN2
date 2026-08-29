package com.example.boilerplate.common.outbox.handler;

import java.util.Set;

/**
 * Hợp đồng cho các handler xử lý payload của outbox event.
 *
 * EventStreamConsumer dựa vào eventType để lookup handler trong EventHandlerRegistry,
 * sau đó gọi handle() để thực thi logic (ví dụ: gửi email).
 *
 * Quy tắc: nếu handle() ném exception, consumer sẽ KHÔNG ACK entry,
 * entry sẽ nằm lại PEL và được PendingReclaimer xử lý (retry hoặc DLQ).
 * Do đó, handler cần đảm bảo idempotent vì có thể được gọi nhiều lần cho cùng một event.
 */
public interface EventHandler {

    /**
     * Các event type mà handler này nhận.
     * EventHandlerRegistry sẽ dùng kết quả của method này để xây dựng
     * routing table (eventType → handler) vào lúc khởi tạo bean.
     *
     * @return tập eventType (ví dụ: {"EMAIL_OTP", "EMAIL_WELCOME"})
     */
    Set<String> supportedTypes();

    /**
     * Xử lý payload JSON của event.
     *
     * @param payloadJson chuỗi JSON nguyên văn từ outbox.payload
     * @throws Exception nếu xử lý thất bại — consumer không ACK, entry sẽ được retry hoặc chuyển DLQ
     */
    void handle(String payloadJson) throws Exception;
}