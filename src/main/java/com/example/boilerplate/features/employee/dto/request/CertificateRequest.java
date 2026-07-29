package com.example.boilerplate.features.employee.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CertificateRequest(
        @NotBlank(message = "BLANK_FIELD")
        @Size(max = 255, message = "OUT_OF_SIZE")
        String name,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String issuer,

        LocalDate issueDate,

        LocalDate expiryDate,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String credentialUrl
) {
}
