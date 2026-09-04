package com.example.boilerplate.common.outbox.entity;

/**
 * Trạng thái của outbox event. Ràng buộc quyền chuyển trạng thái:
 *
 * PENDING  : initial state, do business service thiết lập lúc INSERT event vào db
 * QUEUED   : entry đã được XADD thành công vào Redis Stream. Do listener hoặc polling
 *            scheduler thiết lập, sau khi XADD trả về entry-id.
 * PROCESSING: trạng thái khi consumer dành được quyền xử lí event, event đang trong giai đoạn
 *             được xử lí.
 * SENT      : terminal state - handler đã thực thi thành công và đã ACK. Chỉ EventStreamConsumer
 *             mới được thiết lập trạng thái này. Đây là điều kiện cần cho idempotency check ở             *             consumer (khi xử lí event bị duplicate -> kiểm tra db đã thấy trạng thái
 *             SENT -> bỏ qua và ACK).
 * FAILED    : terminal state - vượt max_retries ở giai đoạn push (registerPushFailure) hoặc
 *             consume path (reclaimer -> DLQ)
 *
 * Sơ đồ chuyển đổi:
 * PENDING -> PROCESSING    - claimProcessing(), atomic, chống trùng
 * PROCESSING -> SENT       - markSent(), sau khi gửi mail OK
 * PROCESSING -> PENDING    - revertToPending(), khi gửi mail fail (để retry)
 *
 * Không tồn tại quá trình chuyển trạng thái từ QUEUED -> PENDING từ consumer;
 * transition ngược QUEUED <- PENDING chỉ do janitor (requeueStaleQueued) thực hiện.
 */
public enum OutboxStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    SENT,
    FAILED
}
