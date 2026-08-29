package com.example.boilerplate.common.outbox.entity;

/**
 * State machine của outbox event. Ràng buộc quyền chuyển trạng thái:
 *
 * PENDING : initial state. Do business service set lúc INSERT (trong TX nghiệp vụ).
 * QUEUED  : entry đã XADD thành công vào Redis Stream. Do listener (fast path)
 *           hoặc polling scheduler set, sau khi XADD trả về entry-id.
 * SENT    : terminal state — handler đã thực thi thành công và đã XACK.
 *           CHỈ EventStreamConsumer được set. Đây là điều kiện cần cho
 *           idempotency check ở consumer (bản duplicate thấy SENT → skip + ACK).
 * FAILED  : terminal state — vượt max_retries ở push path (registerPushFailure)
 *           hoặc consume path (reclaimer → DLQ).
 *
 * Chú ý: không tồn tại transition QUEUED → PENDING từ consumer;
 * transition ngược PENDING ← QUEUED chỉ do janitor (requeueStaleQueued) thực hiện.
 */
public enum OutboxStatus {
    PENDING, QUEUED, SENT, FAILED
}
