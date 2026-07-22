package com.example.boilerplate.features.company.dto.response;

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
        String city,
        String website,
        String jobCount
) {


}
