package com.example.boilerplate.common.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum SuccessCode {

    // ===== Auth - Register =====
    OTP_ATTEMPTS_LIMIT_REACHED_COOLDOWN_ACTIVE(1001, "OTP attempts limit reached, cooldown active", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_NOT_REACHED(1002, "OTP attempts limit reached and wrong limit does not reach", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_NOT_REACHED_COOLDOWN_ACTIVE(1003, "OTP attempts limit not reached, cooldown active", HttpStatus.OK),
    NEW_OTP_CREATED(1004, "New Otp created and send to your email, please check your email", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_NOT_REACHED_OTP_NOT_EXPIRED(1005, "OTP attempts limit not reached and otp not expired", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED(1006, "OTP attempts limit not reached and wrong reached",  HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED(1007, "OTP attempts limit not reached and otp expired", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED(1008, "OTP attempt limit reached, wrong limit not reached and otp not expired", HttpStatus.OK),
    RESEND_OTP_SUCCESS(1009, "Resend new otp success", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED(1010, "OTP attempts limit reached and wrong limit reached", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED(1011, "Otp attempts limit reached and otp expired", HttpStatus.OK),
    COOLDOWN_ACTIVE(1012, "Cooldown is active", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_NOT_REACHED(1013, "OTP Attempt limit not reach and wrong limit not reach", HttpStatus.OK),




    // ===== Auth - Resend OTP =====

    // ===== Auth - Verify OTP =====
    VERIFY_OTP_SUCCESS(3001, "OTP verified successfully", HttpStatus.OK),
    OTP_NOT_MATCH(3006, "OTP does not match", HttpStatus.OK),

    // ===== Auth - Login =====
    LOGIN_SUCCESS(4001, "Login successful", HttpStatus.OK),
    LOGIN_INACTIVE_OTP_SENT(4002, "Account inactive, OTP sent for verification", HttpStatus.OK),
    LOGIN_INACTIVE_OTP_REUSED(4003, "Account inactive, please verify using existing OTP (cooldown active)", HttpStatus.OK),
    OTP_ATTEMPTS_LIMIT_REACHED_AND_COOLDOWN_ACTIVE(4004, "Account inactive, OTP send limit reached, please verify using existing OTP", HttpStatus.OK),
    LOGIN_INACTIVE_OTP_REUSED_NO_COOLDOWN(4005, "Account inactive, please verify using existing OTP", HttpStatus.OK),

    // ===== General =====
    SUCCESS(0, "Success", HttpStatus.OK),
    ;

    SuccessCode(int code, String message, HttpStatusCode httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }

    private final int code;
    private final String message;
    private final HttpStatusCode httpStatusCode;
}
