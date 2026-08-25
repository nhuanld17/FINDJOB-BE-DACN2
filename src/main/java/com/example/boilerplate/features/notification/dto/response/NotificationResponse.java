package com.example.boilerplate.features.notification.dto.response;

import java.time.Instant;

/**
 * NotificationResponse — DTO cho một thông báo trong app (bảng {@code notifications}).
 *
 * Thông báo dạng in-app: lưu vào DB, app mở ra là thấy list
 * (KHÁC push realtime — không cần FCM/WebSocket).
 *
 * @param id        ID của thông báo
 * @param type      Loại thông báo (vd: APPLICATION_NEW, APPLICATION_STATUS...)
 *                  App dựa vào type + role để quyết định mở màn hình nào.
 * @param title     Tiêu đề ngắn gọn (vd: "Có ứng viên mới ứng tuyển")
 * @param content   Nội dung chi tiết
 * @param link      Tham chiếu tới thực thể liên quan — hiện tại lưu jobId dạng
 *                  String (vd: "12"). App parse ra jobId để navigate tới
 *                  màn chi tiết job / danh sách ứng viên. null = không có link.
 * @param isRead    Đã đọc chưa (false = chưa đọc → UI hiện badge/dấu chấm)
 * @param createdAt Thời điểm tạo thông báo (ISO instant)
 */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String content,
        String link,
        Boolean isRead,
        Instant createdAt
) {
}
