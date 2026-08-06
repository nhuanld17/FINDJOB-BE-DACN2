package com.example.boilerplate.features.employee.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * CandidateSummaryResponse — Item trong kết quả tìm kiếm ứng viên
 * (GET /api/v1/employees/search — role COMPANY).
 *
 * Chỉ chứa thông tin TÓM TẮT cho list view (card):
 * - id → bấm vào mở chi tiết qua GET /api/v1/employees/{id} (public profile)
 * - CHỈ trả về khi employee isPublic = true (tôn trọng quyền riêng tư)
 * - Không bao gồm email/phone/address... — chi tiết xem ở màn profile
 */
@Builder
public record CandidateSummaryResponse(
        Long id,
        String fullName,
        String avatarUrl,
        String title,
        String city,
        String bio,
        Boolean isOpenToWork,
        List<String> skills,
        Instant updatedAt
) {
}
