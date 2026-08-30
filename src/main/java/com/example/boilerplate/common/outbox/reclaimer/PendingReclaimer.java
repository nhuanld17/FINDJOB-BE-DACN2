package com.example.boilerplate.common.outbox.reclaimer;

import com.example.boilerplate.common.outbox.config.OutboxStreamConfig;
import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;

import com.example.boilerplate.common.outbox.consumer.EventStreamConsumer;
import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Định kỳ quét PEL (Pending Entries List) của consumer group trên Redis Stream.
 *
 * Khi một message đã được deliver (giao) cho consumer nhưng chưa được XACK,
 * nó nằm trong PEL. Nếu consumer không XACK (do crash hoặc handler throw exception),
 * message sẽ không tự động được deliver lại. Class này có nhiệm vụ:
 * 1. Đọc danh sách các message trong PEL.
 * 2. Lọc những message có thời gian idle vượt quá ngưỡng (reclaimIdleMs).
 * 3. Dùng lệnh XCLAIM để chuyển quyền sở hữu message sang consumer hiện tại.
 * 4. Đọc payload để biết outboxId và số lần deliver (deliveryCount).
 * 5. Nếu deliveryCount < maxRetries, gọi lại EventStreamConsumer để xử lý.
 * 6. Nếu deliveryCount >= maxRetries, chuyển message sang DLQ (dead-letter stream),
 *    XACK khỏi PEL, và cập nhật status của outbox row thành FAILED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingReclaimer {

    private static final String CONSUMER_NAME = OutboxStreamConfig.CONSUMER_NAME;
    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;
    private final EventStreamConsumer eventStreamConsumer;
    private final OutboxRepository outboxRepository;

    /**
     * Chạy mỗi 30 giây (cấu hình qua app.outbox.reclaim-interval-ms).
     * Sử dụng fixedDelay: lần chạy tiếp theo bắt đầu sau khi lần trước kết thúc.
     */
    @Scheduled(fixedDelayString = "${app.outbox.reclaim-interval-ms:30000}")
    public void reclaim(){

        var ops = stringRedisTemplate.opsForStream();
        String streamKey = outboxStreamProperties.streamKey();
        String consumerGroup = outboxStreamProperties.consumerGroup();

        // Lấy tối đa 50 message đang trong PEL (không giới hạn khoảng ID).
        // Spring API không hỗ trợ lọc theo idle trực tiếp, nên phải lấy toàn bộ và lọc sau.
        PendingMessages pendings;
        try {
            pendings = ops.pending(streamKey, consumerGroup, Range.unbounded(), 50);
        } catch (RedisSystemException ex) {
            // NOGROUP: consumer group chưa được tạo (app vừa start, onAppReady chưa chạy).
            // Đây là race lúc startup — bỏ qua, lần chạy sau (30s) group đã có.
            if (ex.getCause() instanceof io.lettuce.core.RedisCommandExecutionException
                    && ex.getCause().getMessage() != null
                    && ex.getCause().getMessage().contains("NOGROUP")) {
                log.debug("Consumer group chưa tồn tại, bỏ qua lượt reclaim này");
                return;
            }
            throw ex;
        }

        if (pendings == null) {
            return;
        }

        for (PendingMessage pendingMessage : pendings) {
            // Bỏ qua nếu message vừa mới được deliver (idle < reclaimIdleMs).
            // Khoảng thời gian này cho phép consumer hiện tại có cơ hội xử lý.
            if (pendingMessage.getElapsedTimeSinceLastDelivery().toMillis() < outboxStreamProperties.reclaimIdleMs()) {
                continue;
            }

            // deliveryCount là số lần Redis đã deliver message này (tăng mỗi lần).
            long deliveryCount = pendingMessage.getTotalDeliveryCount();

            // XCLAIM: chuyển quyền sở hữu message về consumer hiện tại.
            // Cần claim trước để đọc được payload (PEL chỉ chứa metadata, không chứa nội dung).
            List<MapRecord<String, Object, Object>> reclaimed = ops.claim(
                    streamKey, consumerGroup, CONSUMER_NAME,
                    Duration.ofMillis(outboxStreamProperties.reclaimIdleMs()), pendingMessage.getId());

            if (reclaimed.isEmpty()) {
                continue;
            }

            MapRecord<String, Object, Object> raw = reclaimed.getFirst();

            // Ép kiểu từ <String, Object, Object> sang <String, String, String>
            // vì trên thực tế các giá trị đều là chuỗi (do dùng StringRedisTemplate).
            // Giữ nguyên ID của entry để XACK có thể xác nhận đúng entry.
            MapRecord<String, String, String> mapRecord = raw
                    .mapEntries(entry -> Map.entry(
                            String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue())))
                    .withId(raw.getId());

            long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));
            // Lấy maxRetries từ outbox row. Mặc định 5 nếu row không tồn tại (phòng trường hợp bị xóa tay).
            int maxRetries = outboxRepository.findById(outboxId)
                    .map(Outbox::getMaxRetries).orElse(5);

            if (deliveryCount < maxRetries) {
                // Còn lượt thử: gọi lại consumer để xử lý.
                // Lưu ý: container (StreamMessageListenerContainer) không tự động nhận
                // message đã được claim, vì nó chỉ đọc message mới (XREADGROUP >).
                // Vì vậy phải gọi onMessage trực tiếp.
                eventStreamConsumer.onMessage(mapRecord);
                log.info("Reclaimed outbox {} (delivery #{})", outboxId, deliveryCount);
            } else {
                // Đã vượt quá số lần thử tối đa:
                // 1. Ghi nguyên bản message vào DLQ stream (để debug và requeue thủ công),
                // nhưng giới hạn 10000 message
                // 2. Xác nhận (XACK) message khỏi PEL của group.
                // 3. Cập nhật status của outbox row thành FAILED.
                ops.add(StreamRecords.string(mapRecord.getValue())
                                .withStreamKey(outboxStreamProperties.dlqStreamKey()),
                        RedisStreamCommands.XAddOptions.maxlen(10000).approximateTrimming(true));
                ops.acknowledge(streamKey, consumerGroup, pendingMessage.getId());
                outboxRepository.markFailed(outboxId,
                        "Exceeded max deliveries (" + deliveryCount + ")");
                log.error("Outbox {} → DLQ sau {} lần giao", outboxId, deliveryCount);
            }
        }
    }
}