package com.example.boilerplate.features.application.dto.response;

import com.example.boilerplate.common.constant.ApplicationStatus;
import lombok.Builder;

import java.time.Instant;

/**
 * DTO trả về khi COMPANY xem danh sách ứng viên đã apply vào 1 job (list view).
 * Chỉ gồm thông tin cơ bản để nhận diện ứng viên: tên, avatar, email.
 * 
 * Nếu employee có isPublic = false, thông tin cá nhân sẽ được ẩn (null).
 */
@Builder
public record ApplicationSummaryResponse(
        // ===== Thông tin application =====
        Long id,
        ApplicationStatus status,
        String coverLetter,
        String cvUrl,
        Instant appliedAt,

        // ===== Thông tin ứng viên (chỉ cần nhận diện) =====
        Long employeeId,
        String fullName,
        String avatarUrl,
        String email,
        Boolean isPublic
) {
}
