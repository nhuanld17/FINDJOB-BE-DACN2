package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        @NotBlank(message = "BLANK_FIELD")
        String oldPassword,

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 8, max = 72, message = "INVALID_PASSWORD")
        String newPassword
) {
}
