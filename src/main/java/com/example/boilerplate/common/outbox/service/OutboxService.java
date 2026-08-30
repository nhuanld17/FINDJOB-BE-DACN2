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
@Transactional
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Ghi 1 email cần gửi vào bảng outbox (trạng thái PENDING).
     *
     * Phải gọi BÊN TRONG transaction của nghiệp vụ — vd trong hàm register(),
     * cùng lúc với việc lưu user. Nhờ vậy user và row email cùng commit
     * hoặc cùng rollback.
     *
     * Sau khi gọi hàm này, caller phải publishEvent(OutboxSavedEvent)
     * để listener đẩy event vào Redis sau khi commit.
     *
     * Ví dụ:
     *   outboxService.savePending("EMAIL_OTP", "USER", user.getId(),
     *       Map.of("to", email, "templateName", "email/otp",
     *              "variables", Map.of("username", username, "otp", otp)));
     *
     * Biến Map thành JSON mà lỗi → ném exception cho transaction nghiệp vụ
     * rollback. Đây là bug lập trình (payload chứa kiểu không serialize được),
     * nuốt lỗi đồng nghĩa mất email vĩnh viễn.
     */
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
                    .build()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize payload failed: " + eventType, e);
        }
    }

    /**
     * PENDING → QUEUED. Gọi ngay sau khi đẩy event vào Redis thành công.
     * Có 2 nơi gọi: listener (ngay sau commit) và polling scheduler (mỗi 10 giây).
     *
     * @return false = row đã được thằng khác chuyển QUEUED trước rồi
     *         (hai đường cùng đẩy 1 event) — bình thường, không cần làm gì thêm
     *
     * @Transactional riêng (không dựa vào class-level): method này được gọi từ
     * @TransactionalEventListener(AFTER_COMMIT) — lúc đó transaction của request
     * đã commit & đóng, class-level @Transactional không mở TX mới được.
     * Nếu thiếu annotation này, @Modifying UPDATE query chạy không có transaction
     * → TransactionRequiredException "Executing an update/delete query".
     */
    @Transactional
    public boolean markQueued(Long id) {
        return outboxRepository.markQueued(id) > 0;
    }

    /**
     * Claim quyền gửi mail — ATOMIC, chống trùng.
     * PENDING/QUEUED → PROCESSING. Chỉ 1 luồng (trong 8 worker + reclaimer) giành được:
     *   affected = 1 → giành được → gửi mail
     *   affected = 0 → thua (luồng khác đang gửi) → bỏ qua
     *
     * @Transactional riêng: consumer gọi từ thread riêng, cần TX cho @Modifying.
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
     * Ghi nhận 1 lần ĐẨY VÀO REDIS hỏng — polling scheduler gọi khi push lỗi.
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
     * Lấy tối đa `limit` row PENDING (mail cũ trước) đem đi đẩy vào Redis.
     * Polling scheduler gọi mỗi 10 giây.
     *
     * Transaction chỉ sống trong hàm này: SELECT xong là nhả lock và
     * connection ngay — vòng for đẩy Redis của scheduler chạy bên ngoài,
     * không giữ DB connection trong lúc chờ Redis.
     */
    @Transactional
    public List<Outbox> lockPendingBatch(int limit) {
        return outboxRepository.lockPendingBatch(limit);
    }

    /**
     * Janitor: chuyển row QUEUED bị kẹt quá N phút về PENDING.
     * Phải có transaction vì là @Modifying @Query (UPDATE).
     */
    @Transactional
    public int requeueStaleQueued(int minutes) {
        return outboxRepository.requeueStaleQueued(minutes);
    }
}
