package com.example.boilerplate.features.company.dto.response;

import lombok.Builder;

import java.time.Instant;

@Builder
public record CompanyResponse(
        Long id,
        Long ownerId,
        String ownerName,
        String name,
        String slug,
        String description,
        String logoUrl,
        String coverUrl,
        String website,
        String companySize,
        String industry,
        String city,
        String address,
        String email,
        String phone,
        String facebookUrl,
        String linkedinUrl,
        String contactPosition,
        Instant createdAt,
        Instant updatedAt
) {
}
