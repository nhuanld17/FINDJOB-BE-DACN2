package com.example.boilerplate.features.auth.dto.request;

import com.example.boilerplate.common.constant.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 3, max = 50, message = "INVALID_USERNAME")
        @Pattern(
                regexp = "^[a-zA-Z0-9_]+$",
                message = "INVALID_USERNAME_FORMAT"
        )
        String username,

        @NotBlank(message = "BLANK_FIELD")
        @Email(message = "INVALID_EMAIL")
        String email,

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 8, message = "INVALID_PASSWORD")
        String password,

        @NotBlank(message = "BLANK_FIELD")
        String confirmPassword,

        @NotBlank(message = "BLANK_FIELD")
        @Size(max = 100, message = "OUT_OF_SIZE")
        String fullName,

        AccountType accountType,                  // null → coi như USER

        @Size(max = 255, message = "OUT_OF_SIZE")
        String companyName                        // chỉ bắt buộc khi EMPLOYER (check service)
) {
}
