package com.example.boilerplate.common.outbox.producer;

import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.entity.Outbox;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventStreamProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;

    /**
     * Đẩy 1 event vào stream, thành công -> true.
     * Redis chết / mất kết ối -> ném exception cho caller bắt
     */
    public boolean push(Outbox outbox) {
        Map<String, String> fields = new HashMap<>();
        fields.put("outboxId", outbox.getId().toString());
        fields.put("eventType", outbox.getEventType());

        if (outbox.getAggregateType() != null) {
            fields.put("aggregateType", outbox.getAggregateType());
        }

        if (outbox.getAggregateId() != null) {
            fields.put("aggregateId", outbox.getAggregateId().toString());
        }

        fields.put("payload", outbox.getPayload());

        // MAXLEN = 50000: stream chỉ giữ tối đa 50k message, cũ hơn thì redis tự
        // cắt để không làm phình bộ nhớ. nếu message nào bị bắt mất trong khi chưa
        // được xử lí thì polling scheduler sẽ quét event và thêm vào stream lại
        return stringRedisTemplate.opsForStream().add(
                StreamRecords.string(fields).withStreamKey(outboxStreamProperties.streamKey()),
                RedisStreamCommands.XAddOptions.maxlen(outboxStreamProperties.maxlen())
                        .approximateTrimming(true)) != null;
    }
}
