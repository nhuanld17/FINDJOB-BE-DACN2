package com.example.boilerplate.common.outbox.consumer;

import com.example.boilerplate.common.outbox.config.OutboxStreamProperties;
import com.example.boilerplate.common.outbox.handler.EventHandlerRegistry;
import com.example.boilerplate.common.outbox.service.OutboxService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * 6. Thất bại -> revertToPendingWithError (revert + ghi lỗi 1 TX), ko ACK,
 * để message ở lại PEL cho PendingReclaimer xử lí sau (retry hoặc DLQ)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Getter
@Setter
public class EventStreamConsumer {

    private final OutboxService outboxService;
    private final StringRedisTemplate stringRedisTemplate;
    private final OutboxStreamProperties outboxStreamProperties;
    private final EventHandlerRegistry eventHandlerRegistry;

    @Getter
    private volatile boolean running = false;   // mặc định TẮT — OutboxWorkerManager.start() bật
                                                // true sau khi consumer group tồn tại; stop() tắt

    // Vòng lặp poll của 1 worker - chạy trên thread riêng
    public void pollLoop(String workerName) {
        while (running) {
            // 1 lệnh XREADGROUP COUNT=N, BLOCK=pollTimeout — không có message thì trả về list rỗng/null
            // @SuppressWarnings("unchecked") cho warning dưới đây: read(...) nhận varargs StreamOffset<String>... —
            // javac bắt buộc dựng mảng generic (new StreamOffset<String>[] — Java cấm tạo generic array) nên báo
            // "unchecked generic array creation for varargs parameter". Type witness ở điểm 5 chỉ sửa được target-type
            // inference, KHÔNG loại được warning varargs này — phải suppress tại khai báo local (áp cả initializer).
            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> records =
                    stringRedisTemplate.<String, String>opsForStream().read(  // type witness — bắt buộc, xem điểm 5
                            Consumer.from(outboxStreamProperties.consumerGroup(), workerName),
                            StreamReadOptions.empty()
                                    .count(outboxStreamProperties.containerBatchSize())
                                    .block(Duration.ofMillis(outboxStreamProperties.pollTimeoutMs())),
                            StreamOffset.create(outboxStreamProperties.streamKey(),
                                    ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                continue;
            }

            // 2. Claim 1 lần cả lô — RETURNING trả đúng các id được UPDATE thành công
            Set<Long> claimed = new HashSet<>(outboxService.claimProcessingBatch(
                    records.stream()
                            .map(r -> Long.parseLong(r.getValue().get("outboxId")))
                            .toList()));

            List<RecordId> toAck = new ArrayList<>();

            // lặp qua từng records
            for (MapRecord<String, String, String> record : records) {
                long outboxId = Long.parseLong(record.getValue().get("outboxId"));

                // Không được UPDATE (row đã SENT hoặc worker khác đã đổi trước) -> ACK bỏ qua, như nhánh cũ
                if (!claimed.contains(outboxId)) {
                    toAck.add(record.getId());
                    continue;
                }

                // xử lí record
                processRecord(record, toAck);   // KHÔNG claim lại — cả lô đã claim ở bước 2
            }

            // 3. XACK 1 lần cả lô (gồm id được UPDATE + id không được UPDATE + id vào DLQ); message fail KHÔNG nằm trong toAck
            acknowledge(toAck);
        }
    }

    // ===== Đường 1 message — giữ nguyên cho PendingReclaimer (call site không đổi) =====
    public void onMessage(MapRecord<String, String, String> mapRecord) {
        long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));

        // Nhánh reclaimer vẫn claim từng message như cũ
        if (!outboxService.claimProcessing(outboxId)) {
            log.debug("[OUTBOX] Skip outbox={} (already processing or SENT) -> ACK", outboxId);
            acknowledge(List.of(mapRecord.getId()));
            return;
        }

        List<RecordId> toAck = new ArrayList<>();
        processRecord(mapRecord, toAck);
        acknowledge(toAck);
    }

    // ===== Thân cũ của onMessage(MapRecord), thay 3 chỗ ACK thành toAck.add =====
    private void processRecord(MapRecord<String, String, String> mapRecord, List<RecordId> toAck) {
        long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));
        try {
            // Đọc deliveryCount của record
            long deliveryCount = getDeliveryCount(mapRecord);   // XPENDING — giữ nguyên từng message

            // đọc max retries
            int maxRetries = Integer.parseInt(
                    mapRecord.getValue().getOrDefault("maxRetries", "5"));

            // Nếu số lần giao message cho consumer chạm ngưỡng max retries
            if (deliveryCount >= maxRetries) {
                log.warn("[OUTBOX] outbox={} deliveryCount({}) >= maxRetries({}) → DLQ",
                        outboxId, deliveryCount, maxRetries);

                // Đưa vào dead-letter-queue
                sendToDlq(mapRecord);

                // đánh dấu là failed
                outboxService.markFailed(outboxId,
                        "Exceeded max deliveries (" + deliveryCount + ")");

                // ACK cho message này
                toAck.add(mapRecord.getId());                  // DLQ -> XACK
                return;
            }

            // xử lí record theo eventType của record đó
            eventHandlerRegistry.getByEventType(mapRecord.getValue().get("eventType"))
                    .handle(mapRecord.getValue().get("payload"));

            // đánh dấu đã xử lí record
            outboxService.markSent(outboxId);

            // thêm RecordId của record vào danh sách RecordId cần ack
            toAck.add(mapRecord.getId());                      // SENT -> XACK
            log.info("[OUTBOX] SUCCESS outbox={} → SENT + ACK", outboxId);
        } catch (Exception e) {
            // Fail -> revert PROCESSING -> PENDING + ghi lỗi trong 1 TX (1 lệnh UPDATE atomic),
            // KHÔNG XACK — message nằm lại PEL chờ reclaimer
            log.error("Handle fail out={} - revert to PENDING, chờ reclaimer", outboxId, e);
            outboxService.revertToPendingWithError(outboxId, e.getMessage());
        }
    }

    // XACK nhiều id 1 lần — thay cho acknowledge(MapRecord) cũ
    private void acknowledge(List<RecordId> ids) {
        if (ids.isEmpty()) return;
        stringRedisTemplate.opsForStream().acknowledge(
                outboxStreamProperties.streamKey(),
                outboxStreamProperties.consumerGroup(),
                ids.toArray(RecordId[]::new));
    }

    /**
     * Lấy deliveryCount từ PEL - số lần Redis đã giao message này cho consumer
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
}
