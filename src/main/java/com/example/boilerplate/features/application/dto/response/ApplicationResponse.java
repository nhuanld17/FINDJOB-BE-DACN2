package com.example.boilerplate.features.application.dto.response;

import com.example.boilerplate.common.constant.ApplicationStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record ApplicationResponse(
        Long id,
        Long jobId,
        String jobTitle,
        Long companyId,
        String companyName,
        String companyLogoUrl,
        Long employeeId,
        String coverLetter,
        String cvUrl,
        ApplicationStatus status,
        Instant appliedAt
) {
}
