package com.example.boilerplate.features.auth.dto.response;

import lombok.Builder;

@Builder
public record RegisterResponse(
        int code,                // Lấy từ SuccessCode.getCode()
        String message,          // Lấy từ SuccessCode.getMessage()
        Long otpExpiresIn,       // Số giây còn lại cho đến khi otp hết hạn
        Long cooldownRemaining,  // Số giây còn lại để block user không resend otp
        Integer wrongRemaining,   // Số lượt nhập mã OTP còn lại để xác minh
        Long attemptsTTL         // Thời gian TTL của otp:attempts:{userId}
) {
}

