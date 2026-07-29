package com.example.boilerplate.features.company.dto.response;

import com.example.boilerplate.common.constant.City;
import lombok.Builder;

@Builder
public record CompanySummaryResponse (
        Long id,
        String name,
        String slug,
        String logoUrl,
        String coverUrl,
        String industry,
        String companySize,
        City city,
        String website,
        String jobCount,
        int followerCount
) {


}
