package com.example.boilerplate.features.company.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateCompanyRequest(

        @Size(max = 255, message = "OUT_OF_SIZE")
        String name,

        @Size(max = 5000, message = "OUT_OF_SIZE")
        String description,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String logoUrl,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String coverUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String website,

        @Size(max = 50, message = "OUT_OF_SIZE")
        String companySize,

        @Size(max = 100, message = "OUT_OF_SIZE")
        String industry,

        @Size(max = 100, message = "OUT_OF_SIZE")
        String city,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String address,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String email,

        @Size(max = 20, message = "OUT_OF_SIZE")
        String phone,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String facebookUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String linkedinUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String contactPosition
) {
}
