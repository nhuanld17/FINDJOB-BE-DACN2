package com.example.boilerplate.features.job.dto.response;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Builder
public record JobResponse(
        Long id,
        Long companyId,
        String companyName,
        String companySlug,
        String companyLogoUrl,
        Long createdBy,
        String createdByName,
        String title,
        String slug,
        String description,
        String requirements,
        String benefits,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        Integer yearsOfExperience,
        Seniority seniority,
        JobType jobType,
        String location,
        City city,
        List<String> skillsRequired,
        LocalDate expiryDate,
        Integer applyCount,
        JobStatus status,
        boolean deleted,
        boolean expired,
        List<String> categoryNames,
        Instant createdAt,
        Instant updatedAt
) {
}
