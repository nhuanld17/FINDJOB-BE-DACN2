package com.example.boilerplate.common.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ErrorCode {

    // ===== Auth =====
    UNAUTHENTICATED(3001, "Unauthenticated", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(3002, "Access denied", HttpStatus.FORBIDDEN),
    OTP_VERIFICATION_SESSION_EXPIRED(3003, "OTP verification session expired", HttpStatus.FORBIDDEN),
    MAX_WRONG_OTP(3004, "OTP Wrong too many time", HttpStatus.TOO_MANY_REQUESTS),
    OTP_EXPIRED(3005, "OTP is expired or not true", HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS(3008, "Invalid credentials", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(3009, "Unauthorized", HttpStatus.UNAUTHORIZED),
    OTP_VERIFY_LIMIT_REACHED(3010, "OTP verify is blocked because send limit was exceeded", HttpStatus.TOO_MANY_REQUESTS),
    INVALID_DEVICE_ID(3011, "Invalid Device ID", HttpStatus.BAD_REQUEST),
    SESSION_INACTIVE(3012, "Session inactive", HttpStatus.UNAUTHORIZED),
    TOKEN_REUSE_DETECTED(3013,  "Token reuse detected" , HttpStatus.UNAUTHORIZED ),
    ACCESS_TOKEN_EXPIRED(3015, "Access token expired", HttpStatus.UNAUTHORIZED),
    SESSION_DEVICE_MISMATCH(3016, "Session device mismatch", HttpStatus.UNAUTHORIZED),
    

    // ===== Validation =====
    BLANK_FIELD(1001, "Field cannot be blank", HttpStatus.BAD_REQUEST),
    OUT_OF_SIZE(1002, "Field length is out of allowed range", HttpStatus.BAD_REQUEST),
    INVALID_EMAIL(1003, "Invalid email format", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME(1004, "Username must be between {min} and {max} characters", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME_FORMAT(1005, "Username can only contain letters, numbers, and underscores", HttpStatus.BAD_REQUEST),
    INVALID_PASSWORD(1006, "Password must be at least {min} characters", HttpStatus.BAD_REQUEST),
    OTP_OUT_OF_SIZE(1007, "OTP must be 6 characters", HttpStatus.BAD_REQUEST),

    // ===== User / Auth =====
    USER_NOT_FOUND(2001, "User not found", HttpStatus.NOT_FOUND),
    EMAIL_ALREADY_IN_USE(2002, "Email already in use", HttpStatus.CONFLICT),
    USERNAME_ALREADY_IN_USE(2003, "Username already in use", HttpStatus.CONFLICT),
    PASSWORD_MISMATCH(2004, "Password and confirm password does not match", HttpStatus.BAD_REQUEST),
    USER_INACTIVE(2005, "User account is inactive", HttpStatus.FORBIDDEN),
    ACCOUNT_BANNED(2007, "An account using this email was banned", HttpStatus.FORBIDDEN),

    RESEND_OTP_BLOCKED(2009,"Resend OTP blocked" ,HttpStatus.CONFLICT ),
    TOKEN_REVOKED(2010, "Token has been revoked", HttpStatus.UNAUTHORIZED),

    // ===== System =====
    INTERNAL_ERROR(9999, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    ErrorCode(int code, String message, HttpStatusCode httpStatusCode) {
        this.code = code;
        this.message = message;
        this.httpStatusCode = httpStatusCode;
    }

    private final int code;
    private final String message;
    private final HttpStatusCode httpStatusCode;
}
