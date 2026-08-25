package com.example.boilerplate.features.notification.repository;

import com.example.boilerplate.features.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    Page<Notification> findByUserId(Long userId, Pageable pageable);
    Page<Notification> findByUserIdAndIsRead(Long userId, Boolean isRead, Pageable pageable);
    long countByUserIdAndIsRead(Long userId, Boolean isRead);

    /**
     * Đánh dấu TẤT CẢ thông báo chưa đọc của 1 user thành đã đọc.
     * Dùng JPQL bulk update (1 câu UPDATE duy nhất, không load từng entity).
     *
     * @return số dòng bị cập nhật (>= 0)
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user.id = :userId AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") Long userId);
}
