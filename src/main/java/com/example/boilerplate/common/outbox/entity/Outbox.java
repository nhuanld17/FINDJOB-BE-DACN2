package com.example.boilerplate.common.outbox.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Mỗi row = 1 email cần gửi.
 *
 * Row này được ghi CÙNG TRANSACTION với nghiệp vụ — ví dụ hàm register()
 * vừa lưu user vừa ghi row EMAIL_OTP. Commit thì cả hai cùng vào DB,
 * rollback thì cả hai cùng biến mất. Nhờ vậy không bao giờ có trường hợp
 * "tạo user xong mà quên gửi mail".
 *
 * 4 trạng thái của row:
 * PENDING — mới ghi, chưa đưa vào Redis
 * QUEUED  — đã nằm trong Redis, chờ consumer gửi
 * SENT    — mail đã gửi thành công
 * FAILED  — thử quá nhiều lần vẫn hỏng, bỏ cuộc
 */
@Entity
@Table(name = "outbox")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Loại email — vd EMAIL_OTP, EMAIL_WELCOME. Consumer dựa vào đây chọn cách gửi. */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Sự kiện này thuộc về ai — vd USER #42. Chỉ để tra cứu khi debug. */
    @Column(name = "aggregate_type", length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    /**
     * Toàn bộ dữ liệu cần để gửi mail, đóng gói thành JSON:
     * {"to": "nam@gmail.com", "templateName": "email/otp", "variables": {...}}
     *
     * Consumer chỉ đọc payload này, KHÔNG quay lại bảng users lấy thêm —
     * vì giữa lúc ghi và lúc gửi có thể cách nhau lâu (Redis chết...),
     * dữ liệu users có thể đã đổi. Payload là "bản chụp" tại thời điểm ghi.
     *
     * @JdbcTypeCode: bắt buộc để Hibernate ghi chuỗi String vào cột jsonb
     * của PostgreSQL — thiếu là lỗi kiểu dữ liệu lúc INSERT.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    /** Trạng thái hiện tại của row — xem 4 giá trị ở đầu class. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    /**
     * Đếm số lần ĐƯA VÀO REDIS hỏng (Redis chết, mất kết nối...).
     *
     * Lưu ý: KHÔNG đếm lỗi gửi mail. Gửi mail hỏng là chuyện của Redis —
     * Redis tự đếm số lần giao lại message (deliveryCount) và PendingReclaimer
     * dựa vào số đó. Field này chỉ dành cho lỗi phía đẩy.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * Số lần thử tối đa = 5. Thử hỏng đủ 5 lần thì bỏ cuộc (FAILED).
     * Dùng cho cả 2 việc:
     * - Đẩy vào Redis hỏng 5 lần → row FAILED
     * - Gửi mail hỏng 5 lần (Redis giao lại 5 lần) → chuyển vào DLQ
     */
    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    /**
     * Hẹn giờ thử lại: đẩy hỏng thì không thử ngay mà hẹn xa dần
     * (30 giây → 1 phút → 2 phút...). Chưa đến giờ thì polling bỏ qua row.
     * NULL = được thử ngay lập tức.
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /** Lỗi gần nhất — vd "Redis connection timeout". Để soi khi debug. */
    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /**
     * Hibernate tự cập nhật mỗi lần row đổi. Dùng để phát hiện row QUEUED
     * "kẹt" — hơn 15 phút không nhúc nhích thì nghi bản trong Redis đã mất,
     * đưa về PENDING đẩy lại - sẽ có khả năng gửi trùng event nên phải kiểm
     * tra status của outbox event.
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
