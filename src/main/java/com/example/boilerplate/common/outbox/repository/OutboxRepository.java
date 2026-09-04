package com.example.boilerplate.common.outbox.repository;

import com.example.boilerplate.common.outbox.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox,Long> {

    /**
     * Polling batch: SELECT các row PENDING để push vào stream
     * Chỉ lấy các event có status PENDING, số lần retry chưa đạt ngưỡng
     * và đã đến hạn retry
     */
    @Query(value = """
    SELECT * FROM outbox
    WHERE status = 'PENDING'
        AND retry_count < max_retries
        AND (next_retry_at IS NULL OR next_retry_at <= NOW())
    ORDER BY created_at
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<Outbox> lockPendingBatch(@Param("limit") int limit);

    /**
     * Đổi status từ PENDING -> QUEUED. Chỉ đổi khi status đang là PENDING
     */
    @Modifying
    @Query(value = """
        UPDATE outbox SET status = 'QUEUED', next_retry_at = NULL, last_error = NULL
        WHERE id = :id AND status = 'PENDING'
    """, nativeQuery = true)
    int markQueued(@Param("id") Long id);

    /**
     * Event QUEUED quá N phút chưa được xử lí -> khả năng message đã bị mất trong redis
     * -> Đưa về PENDING để polling đẩy vào Stream lại.
     */
    @Modifying
    @Query(value = """
        UPDATE outbox SET status = 'PENDING', next_retry_at = NOW()
        WHERE status = 'QUEUED' and updated_at < NOW() - (:minutes * interval '1 minutes')
    """, nativeQuery = true)
    int requeueStaleQueued(@Param("minutes")int minutes);

    /**
     * Push vào Redis thất bại → đếm 1 lần hỏng (retry_count + 1), lưu lỗi,
     * hẹn giờ thử lại xa dần (30s → 1p → 2p → 4p...). Hỏng đủ max_retries
     * lần (mặc định 5) → chuyển FAILED, thôi thử.
     *
     * Chỉ đếm khi row còn PENDING: row đã QUEUED nghĩa là thằng khác đẩy
     * thành công rồi — không tính hỏng oan.
     */
    @Modifying
    @Query(value = """
        UPDATE outbox
        SET retry_count = retry_count + 1,
            last_error = :error,
            next_retry_at = CASE WHEN retry_count + 1 >= max_retries THEN NULL
                            ELSE now() + least(power(2, retry_count) * interval '30 seconds',
                                                interval '10 minutes') END,
            status = CASE WHEN retry_count + 1 >= max_retries THEN 'FAILED' ELSE status END
        WHERE id = :id AND status = 'PENDING'
    """, nativeQuery = true)
    int registerPushFailure(@Param("id") Long id, @Param("error") String error);

    /**
     * Claim quyền xử lí 1 event
     * Chỉ 1 luồng giành được quyền này:
     * - affected = 1; giành được quyền xử lí event
     * - affected = 0; luồng khác giành được quyền, bỏ qua
     */
    @Modifying
    @Query(value = """
        UPDATE Outbox o SET o.status = 'PROCESSING'
        WHERE o.id = :id AND o.status IN ('PENDING', 'QUEUED')
    """)
    int claimProcessing(@Param("id") Long id);

    /**
     * PROCESSING -> SENT, chỉ consumer được gọi sau khi event được xử lí thành công,
     * và chỉ luồng đã ở trạng thái PROCESSING mới được cập nhật qua SENT.
     */
    @Modifying
    @Query("""
        UPDATE Outbox o SET o.status = 'SENT', o.lastError = NULL
        WHERE o.id = :id AND o.status = 'PROCESSING'
    """)
    int markSent(@Param("id") Long id);

    /**
     * Đổi status từ PROCESSING về PENDING khi xử lí event thất bại
     */
    @Modifying
    @Query("""
        UPDATE Outbox o SET o.status = 'PENDING'
        WHERE o.id = :id AND o.status = 'PROCESSING'
    """)
    int revertToPending(@Param("id") Long id);

    /**
     * Ghi lỗi xử lí event vào last_error để tra cứu.
     */
    @Modifying
    @Query("""
    UPDATE Outbox o SET o.lastError = :error WHERE o.id = :id
    """)
    void noteProcessingError(@Param("id") Long id, @Param("error") String error);

    /**
     * Hết lượt thử (message đã vào DLQ) -> đánh status là FAILED + lưu lí do
     * WHERE status <> 'SENT': nếu consumer vừa gửi mail thành công đúng
     * lúc này thì thôi — SENT là kết quả cuối, không ghi đè.
     */
    @Modifying
    @Query("UPDATE Outbox o SET o.status = 'FAILED', o.lastError = :reason"
            + " WHERE o.id = :id AND o.status <> 'SENT'")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);
}
