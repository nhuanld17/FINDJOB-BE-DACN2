package com.example.boilerplate.features.employee.dto.response;

import com.example.boilerplate.common.constant.City;
import lombok.Builder;

/**
 * DTO trả về khi user xem danh sách company đang follow.
 * Bao gồm thông tin cơ bản của company để hiển thị trên UI.
 */
@Builder
public record FollowedCompanyResponse(
        Long companyId,
        String companyName,
        String companySlug,
        String companyLogoUrl,
        String industry,
        City city
) {
}
