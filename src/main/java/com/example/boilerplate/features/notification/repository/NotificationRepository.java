package com.example.boilerplate.features.notification.repository;

import com.example.boilerplate.features.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    Page<Notification> findByUserId(Long userId, Pageable pageable);
    Page<Notification> findByUserIdAndIsRead(Long userId, Boolean isRead, Pageable pageable);
    long countByUserIdAndIsRead(Long userId, Boolean isRead);
}
