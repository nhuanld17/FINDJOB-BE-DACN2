package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "BLANK_FIELD")
        @Email(message = "INVALID_EMAIL")
        String email,

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 8, message = "INVALID_PASSWORD")
        String password
) {
}
