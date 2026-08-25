package com.example.boilerplate.features.notification.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.notification.dto.response.NotificationResponse;
import com.example.boilerplate.features.notification.entity.Notification;
import com.example.boilerplate.features.notification.repository.NotificationRepository;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * Lấy danh sách thông báo của user hiện tại (phân trang OFFSET, mới nhất trước).
     *
     * @param userId ID của user (lấy từ {@code CustomUserDetails.getId()})
     * @param page   Số trang (bắt đầu từ 0)
     * @param size   Số phần tử mỗi trang
     * @return PaginatedResult {@link NotificationResponse}
     */
    @Transactional(readOnly = true)
    public PaginatedResult<NotificationResponse> getMyNotifications(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> notifPage = notificationRepository.findByUserId(userId, pageable);
        return PaginatedResult.fromPage(notifPage, this::toResponse);
    }

    /**
     * Đếm số thông báo CHƯA ĐỌC của user — dùng cho badge trên icon chuông.
     *
     * @param userId ID của user
     * @return số thông báo chưa đọc (>= 0)
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsRead(userId, false);
    }

    /**
     * Đánh dấu 1 thông báo là đã đọc.
     * Chỉ chủ sở hữu thông báo mới được đánh dấu (khác user → ACCESS_DENIED).
     *
     * @param userId         ID của user hiện tại
     * @param notificationId ID của thông báo
     */
    @Transactional
    public void markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));

        // Chống truy cập chéo: user A không được đánh dấu thông báo của user B
        if (!notification.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notificationRepository.save(notification);
        }
    }

    /**
     * Đánh dấu TẤT CẢ thông báo chưa đọc của user thành đã đọc.
     * Dùng 1 câu UPDATE bulk (không load từng dòng) — nhanh dù list dài.
     *
     * @param userId ID của user hiện tại
     */
    @Transactional
    public void markAllAsRead(Long userId) {
        int updated = notificationRepository.markAllAsReadByUserId(userId);
        if (updated > 0) {
            log.info("Marked {} notifications as read for user {}", updated, userId);
        }
    }

    /**
     * Tạo mới 1 thông báo cho 1 user.
     *
     * Gọi từ service khác khi có sự kiện cần thông báo, vd:
     *  - {@code ApplicationService.apply}: user ứng tuyển → thông báo cho chủ công ty
     *  - {@code ApplicationService.updateApplicationStatus}: đổi trạng thái đơn
     *    → thông báo cho ứng viên
     *
     * Đọc: chỉ truyền user ID (không phải entity) để tránh service gọi tới
     * phải biết entity {@link User} — service này tự load + kiểm tra tồn tại.
     *
     * @param userId  ID của user NHẬN thông báo
     * @param type    Loại thông báo (vd: "APPLICATION_NEW", "APPLICATION_STATUS")
     * @param title   Tiêu đề ngắn
     * @param content Nội dung chi tiết
     * @param link    Tham chiếu thực thể liên quan (hiện tại = jobId dạng String; null nếu không có)
     */
    @Transactional
    public void notifyUser(Long userId, String type, String title, String content, String link) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Notification notification = new Notification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setLink(link);

        notificationRepository.save(notification);
        log.info("Notification [{}] sent to user {}", type, userId);
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.getLink(),
                notification.getIsRead(),
                notification.getCreatedAt()
        );
    }
}
