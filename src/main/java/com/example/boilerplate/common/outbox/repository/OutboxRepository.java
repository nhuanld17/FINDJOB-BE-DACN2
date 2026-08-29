package com.example.boilerplate.common.outbox.repository;

import com.example.boilerplate.common.outbox.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Các mutation trạng thái outbox. Mỗi method là một transition của state machine,
 * đều guard bằng điều kiện WHERE trên status hiện tại để đảm bảo idempotent
 * khi có nhiều writer cạnh tranh (listener vs scheduler vs consumer).
 */
@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /**
     * Polling batch: SELECT các row PENDING để push vào stream.
     *
     * Điều kiện eligibility:
     * - retry_count < max_retries : loại row đã exceed ngưỡng push-failure
     * - next_retry_at IS NULL OR <= now() : chưa đến backoff deadline thì skip
     *
     * FOR UPDATE SKIP LOCKED: row-level lock, các transaction khác gặp row
     * đang lock sẽ bỏ qua thay vì block → multi-instance poll không overlap batch
     * và không serialize nhau. Lock tồn tại trong phạm vi TX của lời gọi này.
     */
    @Query(value = """
        SELECT * FROM outbox
        WHERE status = 'PENDING'
          AND retry_count < max_retries
          AND (next_retry_at IS NULL OR next_retry_at <= now())
        ORDER BY created_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
    List<Outbox> lockPendingBatch(@Param("limit") int limit);

    /**
     * Transition PENDING → QUEUED. Guard status='PENDING': nếu row đã được
     * chuyển bởi writer khác (race giữa fast path và polling), affected rows = 0
     * → caller hiểu là không cần thao tác thêm. Idempotent.
     */
    @Modifying
        @Query(value = """
        UPDATE outbox SET status = 'QUEUED', next_retry_at = NULL, last_error = NULL
        WHERE id = :id AND status = 'PENDING'
    """, nativeQuery = true)
    int markQueued(@Param("id") Long id);

    /**
     * Janitor: khôi phục row QUEUED stale về PENDING.
     * Áp dụng cho các case mất entry phía Redis: FLUSHALL, MAXLEN trim cắt entry
     * chưa consume, consumer group bị xóa. Điều kiện updated_at < now() - N minutes
     * để tránh requeue row đang trong quá trình consume hợp lệ.
     * Side effect: có thể push duplicate vào stream — chấp nhận trong at-least-once.
     */
    @Modifying
    @Query(value = """
        UPDATE outbox SET status = 'PENDING', next_retry_at = now()
        WHERE status = 'QUEUED' AND updated_at < now() - (:minutes * interval '1 minute')
    """, nativeQuery = true)
    int requeueStaleQueued(@Param("minutes") int minutes);

    /**
     * Ghi nhận 1 lần ĐẨY VÀO REDIS THẤT BẠI — polling scheduler gọi khi push lỗi.
     *
     * Một câu UPDATE làm 3 việc:
     * 1. retry_count + 1
     * 2. Lưu lỗi vào last_error (vd "Redis connection timeout")
     * 3. Hẹn giờ thử lại — mỗi lần hỏng hẹn xa GẤP ĐÔI lần trước:
     *
     *      hỏng lần 1 → thử lại sau 30 giây
     *      hỏng lần 2 → sau 1 phút
     *      hỏng lần 3 → sau 2 phút
     *      hỏng lần 4 → sau 4 phút
     *      hỏng lần 5 → FAILED, thôi thử (mặc định max_retries = 5)
     *
     *    Hẹn xa dần để không đánh liên tục vào Redis đang chết — polling chạy
     *    mỗi 10 giây, không hẹn giờ thì cứ 10s lại dội 1 lần.
     *    (Trần tối đa 10 phút/lần — chỉ có tác dụng khi max_retries đặt > 5.)
     *
     * Lưu ý SQL: CASE so sánh "retry_count + 1" với max_retries, trong đó
     * retry_count là giá trị CŨ (PostgreSQL đọc giá trị trước khi UPDATE),
     * nên "+1" chính là số lần hỏng MỚI. Ví dụ: đang 4, max 5 → 4+1=5 ≥ 5 → FAILED.
     *
     * WHERE status = 'PENDING': row đã thành QUEUED (instance khác đẩy thành
     * công rồi) thì câu lệnh không đụng vào — tránh đếm hỏng oan cho row đã xong.
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
     * QUEUED → SENT — CHỈ consumer được gọi, sau khi mail gửi THẬT SỰ thành công
     * (không phải sau khi đẩy vào Redis).
     *
     * WHERE status <> 'SENT': row đã SENT rồi thì câu lệnh không đổi gì
     * → gọi nhầm 2 lần cũng vô hại.
     */
    @Modifying
    @Query("UPDATE Outbox o SET o.status = 'SENT', o.lastError = NULL"
            + " WHERE o.id = :id AND o.status <> 'SENT'")
    int markSent(@Param("id") Long id);

    /**
     * Chỉ ghi lỗi gửi mail gần nhất vào last_error — vd "SMTP timeout".
     * KHÔNG tăng retry_count: số lần gửi lại do Redis đếm (deliveryCount),
     * PendingReclaimer dựa vào số đó. DB đếm thêm là đếm kép.
     */
    @Modifying
    @Query("UPDATE Outbox o SET o.lastError = :error WHERE o.id = :id")
    void noteProcessingError(@Param("id") Long id, @Param("error") String error);

    /**
     * → FAILED — reclaimer gọi sau khi message đã bị chuyển vào DLQ
     * (thử gửi đủ max_retries lần vẫn hỏng).
     *
     * WHERE status <> 'SENT': trong trường hợp consumer vừa gửi thành công
     * đúng lúc reclaimer quyết định đưa xuống DLQ, SENT luôn thắng —
     * không ghi đè kết quả thành công bằng thất bại.
     */
    @Modifying
    @Query("UPDATE Outbox o SET o.status = 'FAILED', o.lastError = :reason"
            + " WHERE o.id = :id AND o.status <> 'SENT'")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);
}
