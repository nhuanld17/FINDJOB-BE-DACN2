package com.example.boilerplate.common.outbox.consumer;

import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.entity.OutboxStatus;
import com.example.boilerplate.common.outbox.handler.EventHandlerRegistry;
import com.example.boilerplate.common.outbox.repository.OutboxRepository;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * Consumer chính: nhận message từ Redis Stream (container giao tới),
 * gọi handler gửi mail thật, rồi tự quyết định ACK
 *
 * Luồng xử lí 1 message:
 * 1. Lấy ra outbox trong DB theo outboxId,
 * 2. Nếu outbox đó đã có status SENT -> ACK và bỏ qua (chống gửi mail trùng)
 * 3. Gọi handler theo eventType để gửi mail
 * 4. Thành công -> markSent() rồi mới ACK
 * 5. Thất bại -> ko ACK, chỉ ghi last_error, để message ở lại PEL
 * cho PendingReclaimer xử lí sau (retry hoặc DLQ)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;
    private final EventHandlerRegistry eventHandlerRegistry;
    private final OutboxService outboxService;

    /**
     * StreamMessageListenerContainer gọi method này mỗi khi có message mới.
     * Với 8 consumer (mục 5.6) method này được gọi SONG SONG từ nhiều thread —
     * instance @Component này được dùng chung, nên phải thread-safe:
     * - Không giữ state mutable (chỉ inject dependencies)
     * - Mỗi message xử lý độc lập, không chia sẻ biến giữa các lần gọi
     *
     * @param mapRecord Message từ Redis Stream, chứa các field:
     *                  outboxId, eventType, payload, aggregateType, aggregateId
     */
    @Override
    public void onMessage(MapRecord<String, String, String> mapRecord) {
        long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));

        /**
         * Giành quyền gửi mail — ATOMIC, chống trùng.
         * PENDING/QUEUED → PROCESSING. Chỉ 1 luồng giành được:
         *   affected = 1 → giành được → gửi mail
         *   affected = 0 → thua (luồng khác đang gửi, hoặc đã SENT) → bỏ qua, chỉ ACK
         */
        if (!outboxService.claimProcessing(outboxId)) {
            log.debug("[OUTBOX] Skip outbox={} (already processing or SENT) → ACK", outboxId);
            acknowledge(mapRecord);
            return;
        }

        try {
            // dispatch theo eventType -> EmailHandler/WebhookHandler
            String eventType = mapRecord.getValue().get("eventType");
            log.info("[OUTBOX] Processing outbox={} eventType={}", outboxId, eventType);

            eventHandlerRegistry.getByEventType(eventType)
                            .handle(mapRecord.getValue().get("payload"));

            // Cập nhật status của event thành SENT nếu xử lí thành công
            outboxService.markSent(outboxId);
            // Sau đó ACK Redis cho event này
            acknowledge(mapRecord);
            log.info("[OUTBOX] SUCCESS outbox={} eventType={} → SENT + ACK", outboxId, eventType);
        } catch (Exception e) {
            // Gửi mail thất bại → revert PROCESSING về PENDING để reclaimer retry.
            // Không ACK — message nằm lại PEL chờ reclaimer claim (min-idle reclaimIdleMs).
            log.error("Handle fail out={} - revert to PENDING, chờ reclaimer", outboxId, e);
            outboxService.revertToPending(outboxId);
            outboxService.noteProcessingError(outboxId, e.getMessage());
            // Lưu ý: không rethrow exception ở đây.
            // Rethrow sẽ làm container log lỗi lặp và nếu PendingReclaimer gọi onMessage()
            // trực tiếp, exception sẽ thoát khỏi vòng lặp reclaim(), khiến các entry còn lại bị bỏ sót.
        }
    }

    private void acknowledge(MapRecord<String, String, String> mapRecord) {
        stringRedisTemplate.opsForStream().acknowledge(outboxStreamProperties.streamKey(),
                outboxStreamProperties.consumerGroup(), mapRecord.getId());
    }
}
