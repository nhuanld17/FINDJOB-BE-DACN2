package com.example.boilerplate.features.application.dto.response;

import com.example.boilerplate.common.constant.ApplicationStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO trả về khi COMPANY xem chi tiết 1 application (detail view).
 * Bao gồm thông tin application + thông tin đầy đủ của ứng viên + job context.
 * <p>
 * Nếu employee có isPublic = false, thông tin cá nhân sẽ được ẩn (null).
 */
@Builder
public record ApplicationDetailResponse(
        // ===== Thông tin application =====
        Long id,
        ApplicationStatus status,
        String coverLetter,
        String cvUrl,
        Instant appliedAt,
        String recruiterNote,
        String rejectedReason,
        Instant reviewedAt,
        Instant respondedAt,

        // ===== Thông tin ứng viên (đầy đủ) =====
        Long employeeId,
        String fullName,
        String avatarUrl,
        String email,
        String phone,
        String title,
        String bio,
        String city,
        String address,
        List<String> skills,
        List<Map<String, Object>> experiences,
        List<Map<String, Object>> education,
        String githubUrl,
        String linkedinUrl,
        String portfolioUrl,
        Boolean isPublic,

        // ===== Thông tin job context =====
        Long jobId,
        String jobTitle,
        Long companyId,
        String companyName,
        String companyLogoUrl
) {
}
