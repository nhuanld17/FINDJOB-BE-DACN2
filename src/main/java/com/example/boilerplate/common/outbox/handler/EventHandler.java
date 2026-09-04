package com.example.boilerplate.common.outbox.handler;

import java.util.Set;

/**
 * Hợp đồng cho các handler xử lí payload của outbox event
 *
 * EventStreamConsumer dựa vào eventType để tìm kiếm handler phù hợp
 * trong EventHandlerRegistry, sau đó gọi handle() để thực thi logic
 */
public interface EventHandler {

    /**
     * Các event type mà handler này nhận.
     * EventHandlerRegistry sẽ dùng kết quả của method này để xây dựng
     * routing table (eventType -> handler) vào lúc khởi tạo bean
     *
     * @return tập eventType (ví dụ: {"EMAIL_OTP", "EMAIL_WELCOME"})
     */
    Set<String> supportedTypes();

    /**
     * Xử lí payload JSON của event
     */
    void handle(String payloadJson) throws Exception;
}
