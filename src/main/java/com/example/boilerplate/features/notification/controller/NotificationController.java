package com.example.boilerplate.features.notification.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.notification.dto.response.NotificationResponse;
import com.example.boilerplate.features.notification.service.NotificationService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API cho chức năng thông báo in-app (bảng {@code notifications}).
 *
 * Tất cả endpoint đều yêu cầu ĐÃ ĐĂNG NHẬP (isAuthenticated) — KHÔNG giới hạn
 * role vì cả USER (ứng viên) lẫn COMPANY (nhà tuyển dụng) đều nhận thông báo:
 *   - USER nhận thông báo khi công ty đổi trạng thái đơn ứng tuyển
 *   - COMPANY nhận thông báo khi có ứng viên mới ứng tuyển vào job của mình
 *
 * Dữ liệu thông báo được sinh bởi {@link NotificationService#notifyUser}
 * (gọi từ ApplicationService) — controller này chỉ đọc + đánh dấu đã đọc.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * Danh sách thông báo của user hiện tại (phân trang, mới nhất trước).
     *
     * @param page Số trang (mặc định 0)
     * @param size Số phần tử mỗi trang (mặc định 20)
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<PaginatedResult<NotificationResponse>>> getMyNotifications(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        PaginatedResult<NotificationResponse> response = notificationService.getMyNotifications(
                userDetails.getId(), page, size
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Số thông báo CHƯA ĐỌC — dùng cho badge trên icon chuông.
     *
     * Trả { "unreadCount": n } — app gọi mỗi khi focus về màn hình chính
     * để cập nhật badge.
     */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<Map<String, Long>>> getUnreadCount(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        long count = notificationService.getUnreadCount(userDetails.getId());
        return ResponseEntity.ok(APIResponse.success(Map.of("unreadCount", count)));
    }

    /**
     * Đánh dấu 1 thông báo là đã đọc.
     * Chỉ chủ sở hữu mới được đánh dấu (khác user → ACCESS_DENIED).
     */
    @PatchMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<Void>> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        notificationService.markAsRead(userDetails.getId(), id);
        return ResponseEntity.ok(APIResponse.success());
    }

    /**
     * Đánh dấu TẤT CẢ thông báo chưa đọc là đã đọc.
     * Dùng 1 câu UPDATE bulk trên DB (nhanh dù list dài).
     */
    @PatchMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<Void>> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        notificationService.markAllAsRead(userDetails.getId());
        return ResponseEntity.ok(APIResponse.success());
    }
}
