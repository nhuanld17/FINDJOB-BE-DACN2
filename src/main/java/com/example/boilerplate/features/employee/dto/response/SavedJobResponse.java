package com.example.boilerplate.features.employee.dto.response;

import com.example.boilerplate.common.constant.JobStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record SavedJobResponse(
        Long jobId,
        String jobTitle,
        String companyName,
        String companySlug,
        String companyLogoUrl,
        Instant savedAt,
        String note,
        JobStatus status,
        boolean expired
) {
}
