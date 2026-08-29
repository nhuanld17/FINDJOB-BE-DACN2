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

/**
 * Nơi duy nhất đẩy event vào Redis Stream (lệnh XADD).
 *
 * Có 2 thằng gọi class này:
 * - OutboxEventListener — đẩy ngay sau khi transaction commit (đường nhanh)
 * - OutboxPollingScheduler — quét mỗi 10 giây, nhặt row PENDING còn bỏ sót (đường dự phòng)
 *
 * 2 đường cùng đẩy 1 event → stream có 2 bản — vô hại: consumer check
 * status == SENT trước khi gửi, bản trùng tới sau sẽ bị bỏ qua.
 */
@Component
@RequiredArgsConstructor
public class EventStreamProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;

    /**
     * Đẩy 1 event vào stream. Thành công → true.
     * Redis chết / mất kết nối → ném exception cho caller bắt.
     *
     * Vì sao không tự bắt lỗi ở đây: mỗi caller xử lí lỗi một kiểu —
     * listener nuốt lỗi (row giữ PENDING, chờ polling), scheduler thì
     * tăng retry_count.
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

        // MAXLEN ~ 50000: stream chỉ giữ tối đa ~50k entry, cũ hơn thì Redis tự cắt
        // để RAM không phình to. Dấu "~" là trim xấp xỉ — có thể cắt nhầm entry
        // chưa ai đọc. Nếu bị cắt, row DB vẫn còn QUEUED → janitor sau 15 phút
        // đưa về PENDING đẩy lại (DB mới là nguồn chân truth, stream chỉ là băng chuyền).
        //
        // Lưu ý API: MAXLEN là tham số của LỆNH XADD (XAddOptions),
        // không phải thuộc tính của record — StreamRecords không có withMaxlen().
        return stringRedisTemplate.opsForStream().add(
                StreamRecords.string(fields).withStreamKey(outboxStreamProperties.streamKey()),
                RedisStreamCommands.XAddOptions.maxlen(outboxStreamProperties.maxlen())
                        .approximateTrimming(true)) != null;
    }
}
