package com.example.boilerplate.features.auth.dto.response;

import lombok.Builder;

/**
 * Response khi đăng nhập đúng mật khẩu nhưng tài khoản chưa active.
 * Mang đúng bộ field như RegisterResponse/ResendOtpResponse để FE tái dùng UI verify OTP.
 */
@Builder
public record LoginInactiveResponse(
        int code,                // SuccessCode 4002/4003/4004
        String message,          // SuccessCode.getMessage()
        Long otpExpiresIn,       // Số giây còn lại cho đến khi OTP hết hạn
        Long cooldownRemaining,  // Số giây còn lại để được phép resend OTP
        Integer wrongRemaining,   // Số lượt nhập OTP còn lại
        Long attemptsTTL          // TTL còn lại của otp:attempt:{userId}
) implements LoginResult {
}
