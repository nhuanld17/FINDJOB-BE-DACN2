package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record LoginRequest(
        @NotBlank(message = "BLANK_FIELD")
        @Email(message = "INVALID_EMAIL")
        @Size(max = 254, message = "INVALID_EMAIL")
        String email,

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 8, max = 1000, message = "INVALID_PASSWORD")
        String password,

        @NotNull(message = "BLANK_FIELD")
        UUID deviceId,

        @NotBlank(message = "BLANK_FIELD")
        @Size(max = 100, message = "OUT_OF_SIZE")
        String deviceName
) {
}
