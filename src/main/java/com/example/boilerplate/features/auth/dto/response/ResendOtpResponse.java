package com.example.boilerplate.features.auth.dto.response;

import lombok.Builder;

@Builder
public record ResendOtpResponse(
    int code,                // Lấy từ SuccessCode.getCode()
    String message,          // Lấy từ SuccessCode.getMessage()
    Long otpExpiresIn,       // Seconds remaining until OTP expires
    Long cooldownRemaining,  // Seconds remaining until cooldown ends
    Integer wrongRemaining,   // Remaining wrong attempts (5 - current wrong count)
    Long attemptsTTL        // TTL còn lại của attempts
) {

}
