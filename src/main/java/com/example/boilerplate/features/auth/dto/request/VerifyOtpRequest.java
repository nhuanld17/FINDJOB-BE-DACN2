package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest (
        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 6, max = 6, message = "OTP_OUT_OF_SIZE")
        String otp
) {
}
