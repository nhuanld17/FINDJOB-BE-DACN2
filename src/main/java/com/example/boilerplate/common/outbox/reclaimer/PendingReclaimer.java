package com.example.boilerplate.common.outbox.reclaimer;

import com.example.boilerplate.common.outbox.config.OutboxStreamConfig;
import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.consumer.EventStreamConsumer;
import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.repository.OutboxRepository;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingReclaimer {

    private static final String CONSUMER_NAME = OutboxStreamConfig.CONSUMER_NAME;
    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;
    private final OutboxRepository outboxRepository;
    private final EventStreamConsumer eventStreamConsumer;
    private final OutboxService outboxService;

    // Chạy nền mỗi 30 giây
    @Scheduled(fixedDelayString = "${app.outbox.reclaim-interval-ms:30000}")
    public void reclaim() {
        var ops = stringRedisTemplate.opsForStream();
        String streamKey = outboxStreamProperties.streamKey();
        String consumerGroup = outboxStreamProperties.consumerGroup();

        // Lấy tối đa 50 message đang nằm trong PEL (không giới hạn khoảng ID).
        // Spring API không hỗ trợ lọc theo idle trực tiếp — lấy toàn bộ rồi lọc
        // ở vòng lặp bên dưới theo reclaimIdleMs (mặc định 60 giây).
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
            // Bỏ qua nếu message vừa mới được deliver (idle < reclaimIdleMs)
            if (pendingMessage.getElapsedTimeSinceLastDelivery().toMillis() < outboxStreamProperties.reclaimIdleMs()) {
                continue;
            }

            // deliveryCount là số lần Redis đã chuyển message này
            long deliveryCount = pendingMessage.getTotalDeliveryCount();

            // XCLAIM: chuyển quyền sở hữu message này về lại consumer hiện tại
            // cần claim trước để đọc payload
            List<MapRecord<String, Object, Object>> reclaimed = ops.claim(
                    streamKey, consumerGroup, CONSUMER_NAME,
                    Duration.ofMillis(outboxStreamProperties.reclaimIdleMs()), pendingMessage.getId());

            if (reclaimed.isEmpty()) {
                continue;
            }

            MapRecord<String, Object, Object> raw = reclaimed.getFirst();

            // Ép kiểu từ <String, Object, Object> sang <String, String, String>
            // Vì thực tế các giá trị đều là chuỗi (do dùng StringRedisTemplate)
            // giữ nguyên ID của entry để XACK có thể xác nhận đúng entry
            MapRecord<String, String, String> mapRecord = raw
                    .mapEntries(entry -> Map.entry(
                            String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue())))
                    .withId(raw.getId());

            long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));

            // lấy maxRetries từ event. Mặc định là 5 nếu row ko tồn tại
            int maxRetries = outboxRepository.findById(outboxId)
                    .map(Outbox::getMaxRetries).orElse(5);

            if (deliveryCount < maxRetries) {
                // Còn lượt thử: gọi lại consumer để xử lí
                eventStreamConsumer.onMessage(mapRecord);
                log.info("Reclaimed outbox {} (delivery #{})", outboxId, deliveryCount);
            } else {
                // Vượt quá số lần thử tối đa
                // 1. Ghi nguyên bản message này vào DLQ Stream (giới hạn 10000 messsage)
                // 2. Xác nhận XACK cho message này khỏi PEL của group
                // 3. Cập nhật status của outbox row thành FAILED
                ops.add(StreamRecords.string(mapRecord.getValue())
                        .withStreamKey(outboxStreamProperties.dlqStreamKey()),
                        RedisStreamCommands.XAddOptions.maxlen(10000).approximateTrimming(true)
                );
                ops.acknowledge(streamKey, consumerGroup, pendingMessage.getId());
                outboxService.markFailed(outboxId, "Exceeded max deliveries (" + deliveryCount + ")");
                log.error("Outbox {} → DLQ sau {} lần giao", outboxId, deliveryCount);
            }
        }
    }
}
