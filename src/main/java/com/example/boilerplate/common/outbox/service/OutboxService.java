package com.example.boilerplate.common.outbox.service;

import com.example.boilerplate.common.outbox.entity.Outbox;
import com.example.boilerplate.common.outbox.entity.OutboxStatus;
import com.example.boilerplate.common.outbox.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Ghi 1 event cần xử lí vào bảng outbox (trạng thái PENDING)
     */
    @Transactional
    public Outbox savePending(String eventType, String aggregateType,
                                   Long aggregateId, Map<String, Object> payload) {
        try {
            return outboxRepository.save(Outbox.builder()
                            .eventType(eventType)
                            .aggregateType(aggregateType)
                            .aggregateId(aggregateId)
                            .payload(objectMapper.writeValueAsString(payload))
                            .status(OutboxStatus.PENDING)
                            .retryCount(0)
                            .maxRetries(5)
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serial payload failed: " + eventType, e);
        }
    }

    /**
     *  Đổi status PENDING -> QUEUED. Gọi ngay sau khi đẩy event vào Redis thành công
     * (listener sau commit hoặc polling mỗi 10 giây).
     *
     * @return false = thằng khác đã chuyển QUEUED trước rồi (2 đường cùng
     *         đẩy 1 event) — bình thường, bỏ qua.
     *
     * Cần @Transactional riêng: method được gọi từ listener AFTER_COMMIT,
     * lúc đó TX cũ đã đóng - thiếu annotation thì @Modifying UPDATE lỗi
     * TransactionRequiredException.
     */
    @Transactional
    public boolean markQueued(Long id) {
        return outboxRepository.markQueued(id) > 0;
    }

    /**
     * Claim quyền gửi mail - ATOMIC, chống trùng.
     * PENDING/QUEUED → PROCESSING. Chỉ 1 luồng (trong 8 worker + reclaimer) giành được:
     *   affected = 1 → giành được → gửi mail
     *   affected = 0 → thua (luồng khác đang gửi) → bỏ qua
     */
    @Transactional
    public boolean claimProcessing(Long id) {
        return outboxRepository.claimProcessing(id) > 0;
    }

    /**
     * PROCESSING → SENT. CHỈ consumer được gọi — sau khi mail gửi THẬT thành công.
     *
     * Thứ tự bắt buộc: markSent() TRƯỚC, XACK Redis SAU.
     * Nếu app crash giữa 2 bước → Redis giao lại message → consumer thấy
     * row đã SENT → chỉ ACK, không gửi mail lần nữa. Đảo ngược thứ tự
     * (ACK trước) mà crash = mail mất trắng, không ai giao lại nữa.
     *
     * @Transactional riêng: consumer gọi từ thread riêng, cần TX cho @Modifying.
     */
    @Transactional
    public boolean markSent(Long id) {
        return outboxRepository.markSent(id) > 0;
    }

    /**
     * Gửi mail thất bại → revert PROCESSING → PENDING để retry.
     * Chỉ revert nếu vẫn còn PROCESSING (chưa bị luồng khác đụng).
     *
     * @Transactional riêng: consumer gọi từ thread riêng, cần TX cho @Modifying.
     */
    @Transactional
    public void revertToPending(Long id) {
        outboxRepository.revertToPending(id);
    }

    /**
     * Ghi nội dung lỗi gần nhất của consumer vào cột last_error để debug.
     * Không tăng retry - phía redis đã có deliveryCount tự tăng mỗi lần
     * giao lại message.
     *
     * @Transactional riêng: consumer gọi từ thread riêng, cần TX cho @Modifying.
     */
    @Transactional
    public void noteProcessingError(Long id, String error) {
        outboxRepository.noteProcessingError(id, error);
    }

    /**
     * → FAILED. Consumer (hết lượt: deliveryCount >= maxRetries) hoặc reclaimer
     * gọi sau khi message đã bị chuyển vào DLQ — thử đủ lượt vẫn hỏng.
     *
     * Guard WHERE status <> 'SENT': consumer vừa gửi mail thành công đúng lúc
     * thằng kia quyết định đưa message xuống DLQ → SENT luôn thắng, không
     * ghi đè kết quả thành công bằng thất bại.
     *
     * @Transactional riêng: consumer/reclaimer gọi từ thread riêng, cần TX cho @Modifying.
     */
    @Transactional
    public void markFailed(Long id, String reason) {
        outboxRepository.markFailed(id, reason);
    }

    /**
     * Ghi nhận 1 lần ĐẨY VÀO REDIS hỏng - polling scheduler gọi khi push lỗi.
     * Chi tiết nằm trong 1 câu UPDATE ở repository: retry_count + 1,
     * lưu lỗi, hẹn giờ thử lại xa dần (30s → 1p → 2p → 4p).
     * Hỏng đủ 5 lần → row FAILED, thôi thử.
     *
     * @Transactional riêng: polling scheduler gọi từ thread scheduling-1,
     * không có transaction context sẵn — cần TX cho @Modifying UPDATE query.
     */
    @Transactional
    public void registerPushFailure(Long id, String error) {
        outboxRepository.registerPushFailure(id, error);
    }

    /**
     * Lấy tối đa limit row PENDING (mail cũ trước) đem đi đẩy vào Redis.
     * Polling scheduler gọi mỗi 10 giây.
     */
    @Transactional
    public List<Outbox> lockPendingBatch(int limit) {
        return outboxRepository.lockPendingBatch(limit);
    }

    /**
     * Chuyển row QUEUED bị kẹt quá N phút về PENDING.
     * Phải có transaction vì là @Modifying @Query (UPDATE).
     */
    @Transactional
    public int requeueStaleQueued(int minutes) {
        return outboxRepository.requeueStaleQueued(minutes);
    }
}
