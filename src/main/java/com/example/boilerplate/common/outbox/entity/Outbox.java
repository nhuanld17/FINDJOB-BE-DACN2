package com.example.boilerplate.common.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 1 outbox = 1 event cần xử lí
 *
 * Row này được ghi CÙNG TRANSACTION với nghiệp vụ — ví dụ hàm register()
 * vừa lưu user vừa ghi row EMAIL_OTP. Commit thì cả hai cùng vào DB,
 * rollback thì cả hai cùng biến mất. Nhờ vậy không bao giờ có trường hợp
 * "tạo user xong mà quên gửi mail".
 *
 * 5 trạng thái của row:
 * PENDING    — mới ghi, chưa đưa vào Redis
 * QUEUED     — đã nằm trong Redis, chờ consumer gửi
 * PROCESSING — consumer đang giành quyền gửi mail (atomic claim)
 * SENT       — mail đã gửi thành công
 * FAILED     — thử quá nhiều lần vẫn hỏng, bỏ cuộc
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Loại event - EMAIL_OTP, EMAIL_WELCOME, consumer sẽ dựa vào đây để chọn cách
     * xử lí
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * Loại đối tượng nghiệp vụ liên quan đến event này - VD: USER, JOB, APPLICATION
     * Kết hợp với aggregateId để truy vết khi debug, KHÔNG dùng trong xử lí logic
     */
    @Column(name = "aggregate_type", length = 50)
    private String aggregateType;

    /**
     * ID của đối tượng nghiệp vụ - VD: 42 (tức là USER 42)
     * Chỉ để debug, ko dùng trong logic xử lí
     */
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

    /**
     * Trạng thái hiện tại của event
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    /**
     * Đếm số lần đẩy event vào redis stream lỗi (redis chết, mất kết nối)
     *
     * Lưu ý: Không đếm lỗi xử lí event. Xử lí event lỗi là thuộc về redis -
     * redis tự đếm số lần giao lại message (deliveryCount) trong PEL và
     * PendingReclaimer dựa vào số đó:
     * - deliveryCount < maxRetries -> còn lượt -> XCLAIM claim lại -> xử lí lại
     * - deliveryCount >= maxRetries -> hết lượt -> chuyển qua DLQ + markFailed
     *
     * Field này chỉ dành cho lỗi ở phía đẩy.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * Số lần thử tối đa = 5. Thử hỏng đủ 5 lần thì bỏ cuộc (FAILED)
     * Dùng cho 2 việc:
     * - Đẩy message vào redis hỏng -> row FAILED
     * - Gửi mail hỏng 5 lần (Redis giao lại 5 lần) -> chuyển vào DLQ
     */
    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    /**
     * Hẹn giờ thử lại: đẩy hỏng thì không đẩy lại ngay mà hẹn xa dần
     * (30 giây -> 1 phút -> 2 phút ...). Chưa đến giờ thì polling bỏ qua row.
     * NULL -> được thử đẩy lại vào stream ngay lập tức
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * Lỗi gần nhất - VD: "Redis Connection Timeout". Để debug
     */
    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
