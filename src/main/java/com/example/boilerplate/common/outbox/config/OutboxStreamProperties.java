package com.example.boilerplate.common.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties cho outbox module (prefix: app.outbox).
 * Tất cả giá trị đều có thể override qua biến môi trường hoặc command line.
 *
 * Các giá trị mặc định được định nghĩa trong application.yml.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxStreamProperties(
        /**
         * Tên Redis Stream chính — nơi producer XADD entry vào.
         * Mặc định: findjob:event-queue
         */
        String streamKey,

        /**
         * Tên Redis Stream DLQ (Dead Letter Queue) — các entry đã được thử gửi mail
         * vượt quá số lần cho phép (maxRetries) sẽ được move vào đây để debug và xử lý thủ công.
         * Số lần thử là deliveryCount do Redis quản lý, không phải retry_count trong DB.
         * Mặc định: findjob:event-dlq
         */
        String dlqStreamKey,

        /**
         * Tên consumer group — tất cả instance trong cùng group chia sẻ việc
         * consume entry (mỗi entry chỉ 1 consumer nhận).
         * Mặc định: findjob-workers
         */
        String consumerGroup,

        /**
         * Chu kỳ polling scheduler (đường fallback) — đơn vị ms.
         * Mặc định: 10000 (10 giây)
         * Lưu ý: @Scheduled dùng placeholder trực tiếp, field này chỉ để document.
         */
        long pollIntervalMs,

        /**
         * Thời gian BLOCK của XREADGROUP khi stream trống — container chờ tối đa
         * bao lâu trước khi poll lại.
         * Mặc định: 2000 (2 giây)
         */
        long pollTimeoutMs,

        /**
         * Số lượng row PENDING tối đa được lấy mỗi vòng polling.
         * Mặc định: 100
         */
        int batchSize,

        /**
         * Giới hạn số entry tối đa trong stream chính (MAXLEN ~).
         * Trim xấp xỉ để giữ RAM Redis có giới hạn.
         * Mặc định: 50000
         */
        long maxlen,

        /**
         * Ngưỡng (phút) để janitor phát hiện row QUEUED bị kẹt.
         * Row QUEUED có updated_at cũ hơn giá trị này sẽ được đưa về PENDING.
         * Mặc định: 15 phút
         */
        int staleQueuedMinutes,

        /**
         * Chu kỳ quét PEL (Pending Entries List) của PendingReclaimer — đơn vị ms.
         * Mặc định: 30000 (30 giây)
         * Lưu ý: @Scheduled dùng placeholder trực tiếp, field này chỉ để document.
         */
        long reclaimIntervalMs,

        /**
         * Thời gian idle tối thiểu (ms) để reclaimer claim một entry từ PEL.
         * Dưới ngưỡng này, entry được coi là đang được consumer xử lý hợp lệ.
         * Đồng thời là khoảng thời gian tối thiểu giữa các lần retry phía consume.
         * Mặc định: 60000 (60 giây)
         */
        long reclaimIdleMs
) {}
