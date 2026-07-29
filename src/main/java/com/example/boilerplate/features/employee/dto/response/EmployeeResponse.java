package com.example.boilerplate.features.employee.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Builder
public record EmployeeResponse(
        Long id,
        Long userId,
        String fullName,
        String avatarUrl,
        String phone,
        LocalDate dateOfBirth,
        String gender,
        String city,
        String address,
        String cvUrl,
        String githubUrl,
        String linkedinUrl,
        String portfolioUrl,
        Boolean isPublic,
        Boolean isOpenToWork,
        String title,
        String bio,
        List<String> skills,
        List<Map<String, Object>> experiences,
        List<Map<String, Object>> education,
        List<CertificateResponse> certificates,
        Instant createdAt,
        Instant updatedAt
) {
    @Builder
    public record CertificateResponse(
            Long id,
            String name,
            String issuer,
            LocalDate issueDate,
            LocalDate expiryDate,
            String credentialUrl
    ) {
    }
}
