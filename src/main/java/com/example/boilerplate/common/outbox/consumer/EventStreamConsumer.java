package com.example.boilerplate.common.outbox.consumer;

import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.handler.EventHandlerRegistry;
import com.example.boilerplate.common.outbox.repository.OutboxRepository;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Range;

/**
 * Consumer chính: nhận message từ Redis Stream (container giao tới),
 * gọi handler xử lí event, rồi tự quyết định ACK
 *
 * Luồng xử lí 1 event:
 * 1. Claim quyền xử lí event
 * 2. Nếu claim fail -> ACK và bỏ qua
 * 3. Kiểm tra deliveryCount >= maxRetries -> hết lượt, chuyển DLQ + markFailed + ACK
 * 4. Gọi handler theo eventType để xử lí event
 * 5. Thành công -> markSent() rồi mới ACK
 * 6. Thất bại -> revertToPending, ko ACK, để message ở lại PEL
 * cho PendingReclaimer xử lí sau (retry hoặc DLQ)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final OutboxService outboxService;
    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;
    private final OutboxRepository outboxRepository;
    private final EventHandlerRegistry eventHandlerRegistry;

    /**
     * StreamMessageListenerContainer gọi method này mỗi khi có message mới.
     * Với 8 consumer, method này được gọi đồng thời (cũng có lúc song song)
     * từ nhiều thread - instance @Component này được dùng chung, nên phải
     * thread-safe:
     * - Không giữ state mutable
     * - Mỗi message xử lí độc lập, không chia sẻ biến giữa các lần gọi
     * @param mapRecord Message từ Redis Stream, chứa các field:
     *                  outboxId, eventType, payload, aggregateType, aggregateId
     */
    @Override
    public void onMessage(MapRecord<String, String, String> mapRecord) {
        long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));

        /**
         * Giành quyền xử lí event bằng cách đổi status từ PENDING/QUEUED -> PROCESSING
         * Chỉ 1 luồng giành được:
         * - affected = 1 -> giành được -> xử lí event;
         * - affected = 0 -> thua (do luồng khác đang xử lí, hoặc đã SENT) -> bỏ qua, chỉ ACK
         */
        if (!outboxService.claimProcessing(outboxId)) {
            log.debug("[OUTBOX] Skip outbox={} (already processing or SENT) -> ACK", outboxId);
            acknowledge(mapRecord);
            return;
        }

        try {
            // Kiểm tra deliveryCount: nếu message đã bị giao lại quá maxRetries
            // lần mà chưa XACK → hết lượt thử, chuyển DLQ luôn, không cố xử lý nữa.
            // Tránh lãng phí 1 lần gửi mail nữa khi biết trước sẽ fail.
            long deliveryCount = getDeliveryCount(mapRecord);
            int maxRetries = outboxRepository.findById(outboxId)
                    .map(Outbox::getMaxRetries).orElse(5);

            if (deliveryCount >= maxRetries) {
                log.warn("[OUTBOX] outbox={} deliveryCount({}) >= maxRetries({}) → DLQ",
                        outboxId, deliveryCount, maxRetries);
                sendToDlq(mapRecord);
                outboxService.markFailed(outboxId,
                        "Exceeded max deliveries (" + deliveryCount + ")");
                acknowledge(mapRecord);
                return;
            }

            // dispatch theo eventType -> EmailHandler/DoSomeThingHandler
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
            // Xử lí event thất bại -> revert PROCESSING về PENDING để reclaimer retry.
            // Không ACK — message nằm lại PEL chờ reclaimer claim (min-idle reclaimIdleMs).
            log.error("Handle fail out={} - revert to PENDING, chờ reclaimer", outboxId, e);
            outboxService.revertToPending(outboxId);
            outboxService.noteProcessingError(outboxId, e.getMessage());
        }
    }

    /**
     * Lấy deliveryCount từ PEL — số lần Redis đã giao message này cho consumer
     * mà chưa nhận XACK. Dùng XPENDING tra theo message ID.
     */
    private long getDeliveryCount(MapRecord<String, String, String> mapRecord) {

        var pending = stringRedisTemplate.opsForStream().pending(
                outboxStreamProperties.streamKey(),
                outboxStreamProperties.consumerGroup(),
                Range.just(mapRecord.getId().getValue()), // start = end = ID message đang check
                1);                             // count: range chỉ 1 ID nên tối đa 1 message khớp, để 1 là đủ

        // Nếu có pending thì lấy ra DeliveryCount của message pending đó
        if (pending != null && !pending.isEmpty()) {
            return pending.get(0).getTotalDeliveryCount();
        }

        return 0;
    }

    /**
     * Chuyển message sang DLQ (dead-letter stream) để debug/requeue thủ công.
     * Giới hạn 10000 entry trong DLQ bằng MAXLEN ~.
     */
    private void sendToDlq(MapRecord<String, String, String> mapRecord) {
        stringRedisTemplate.opsForStream().add(
                StreamRecords.string(mapRecord.getValue())
                        .withStreamKey(outboxStreamProperties.dlqStreamKey()),
                RedisStreamCommands.XAddOptions.maxlen(10000)
                        .approximateTrimming(true));
    }

    private void acknowledge(MapRecord<String, String, String> mapRecord) {
        stringRedisTemplate.opsForStream().acknowledge(outboxStreamProperties.streamKey(),
                outboxStreamProperties.consumerGroup(), mapRecord.getId());
    }
}
