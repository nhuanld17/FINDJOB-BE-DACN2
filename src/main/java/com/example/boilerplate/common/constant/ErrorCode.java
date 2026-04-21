package com.example.boilerplate.common.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ErrorCode {

    // ===== Auth =====
    UNAUTHENTICATED(3001, "Unauthenticated", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(3002, "Access denied", HttpStatus.FORBIDDEN),


    // ===== Validation =====
    BLANK_FIELD(1001, "Field cannot be blank", HttpStatus.BAD_REQUEST),
    OUT_OF_SIZE(1002, "Field length is out of allowed range", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1003, "Invalid email format", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME(1004, "Username must be between {min} and {max} characters", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME_FORMAT(1005, "Username can only contain letters, numbers, and underscores", HttpStatus.BAD_REQUEST),
    INVALID_PASSWORD(1006, "Password must be at least {min} characters", HttpStatus.BAD_REQUEST),

    // ===== User / Auth =====
    USER_NOT_FOUND(2001, "User not found", HttpStatus.NOT_FOUND),
    EMAIL_ALREADY_IN_USE(2002, "Email already in use", HttpStatus.CONFLICT),
    USERNAME_ALREADY_IN_USE(2003, "Username already in use", HttpStatus.CONFLICT),
    PASSWORD_MISMATCH(2004, "Password and confirm password does not match", HttpStatus.BAD_REQUEST),
    USER_INACTIVE(2005, "User account is inactive", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS(2006, "Invalid username or password", HttpStatus.UNAUTHORIZED),
    ACCOUNT_BANNED(2007, "An account using this email was banned", HttpStatus.FORBIDDEN),
    TOO_MANY_OTP_ATTEMPTS(2008, "OTP was resend too many time", HttpStatus.TOO_MANY_REQUESTS),
    RESEND_OTP_BLOCKED(2009,"Resend OTP blocked" ,HttpStatus.CONFLICT ),

    // ===== System =====
    INTERNAL_ERROR(9999, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    ErrorCode(int code, String message, HttpStatusCode httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }

    private final int code;
    private final String message;
    private final HttpStatusCode httpStatusCode;
}
