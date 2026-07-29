package com.example.boilerplate.features.company.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO cho Company Dashboard — thống kê tổng quan.
 */
public record CompanyStatsResponse(
        long totalJobs,
        long activeJobs,
        long totalApplicants,
        int totalFollowers,
        List<RecentApplication> recentApplications,
        Map<String, Long> applicationsByStatus
) {
    public record RecentApplication(
            Long applicationId,
            String jobTitle,
            String employeeName,
            String avatarUrl,
            Instant appliedAt
    ) {}
}
