package com.example.boilerplate.common.outbox.handler;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry ánh xạ eventType -> EventHandler
 *
 * Được xây dựng tự động từ tất cả các bean EventHandler trong Spring Context.
 * Mỗi handler khai báo tập eventType mình xử lí qua supportedTypes().
 *
 * Consumer dùng registry này để dispatch payload tới handler đúng loại.
 * Nếu không tìm thấy handler cho eventType -> throw Exception -> consumer
 * không ACK -> entry sẽ bị reclaimer đưa vào DLQ (không bị mất message)
 */
@Component
public class EventHandlerRegistry {

    private final Map<String, EventHandler> byEventType = new ConcurrentHashMap<>();

    /**
     * Constructor injection: Spring cung cấp tất cả bean EventHandler.
     * Registry tự đăng kí từng handler với các eventType nó hỗ trợ.
     */
    public EventHandlerRegistry(List<EventHandler> allHandlers) {
        allHandlers.forEach(h -> h.supportedTypes()
                .forEach(type -> byEventType.put(type, h)));
    }

    /**
     * Lấy handler theo eventType.
     *
     * @throws IllegalStateException nếu không tìm thấy handler — đây là lỗi
     *         cấu hình, cần được surface để entry vào DLQ thay vì bị bỏ qua.
     */
    public EventHandler getByEventType(String eventType) {
        EventHandler eventHandler = byEventType.get(eventType);
        if (eventHandler == null) {
            throw new IllegalStateException("No handler for event type " + eventType);
        }
        return eventHandler;
    }
}
