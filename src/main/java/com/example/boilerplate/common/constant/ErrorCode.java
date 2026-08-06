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

    // ===== Company =====
    COMPANY_NOT_FOUND(2011, "Company not found", HttpStatus.NOT_FOUND),
    COMPANY_ALREADY_EXISTS(2012, "User already has a company", HttpStatus.CONFLICT),
    COMPANY_NAME_REQUIRED(2013, "Company name is required for employer registration", HttpStatus.BAD_REQUEST),

    // ===== Employee =====
    EMPLOYEE_NOT_FOUND(2014, "Employee profile not found", HttpStatus.NOT_FOUND),
    EMPLOYEE_ALREADY_EXISTS(2015, "User already has an employee profile", HttpStatus.CONFLICT),
    EMPLOYEE_PROFILE_INCOMPLETE(2016, "Employee profile is incomplete", HttpStatus.BAD_REQUEST),

    // ===== Job =====
    JOB_NOT_FOUND(2017, "Job not found", HttpStatus.NOT_FOUND),
    JOB_ALREADY_CLOSED(2018, "Job is already closed", HttpStatus.BAD_REQUEST),
    JOB_EXPIRED(2019, "Job has expired", HttpStatus.BAD_REQUEST),
    JOB_SLUG_DUPLICATE(2020, "Job slug already exists in this company", HttpStatus.CONFLICT),
    EXPIRY_DATE_IN_PAST(2029, "Expiry date cannot be in the past", HttpStatus.BAD_REQUEST),

    // ===== Application =====
    APPLICATION_NOT_FOUND(2021, "Application not found", HttpStatus.NOT_FOUND),
    APPLICATION_ALREADY_EXISTS(2022, "You have already applied to this job", HttpStatus.CONFLICT),
    APPLICATION_NOT_OWNER(2023, "This application does not belong to you", HttpStatus.FORBIDDEN),
    APPLICATION_CANNOT_CANCEL(2030, "Application cannot be cancelled in its current status", HttpStatus.BAD_REQUEST),
    INVALID_APPLICATION_STATUS(2034, "Invalid application status transition", HttpStatus.BAD_REQUEST),
    REJECTED_REASON_REQUIRED(2035, "Rejected reason is required when rejecting an application", HttpStatus.BAD_REQUEST),
    APPLICATION_ALREADY_FINALIZED(2039, "Cannot modify a finalized application", HttpStatus.BAD_REQUEST),

    // ===== Category =====
    CATEGORY_NOT_FOUND(2024, "Category not found", HttpStatus.NOT_FOUND),
    CATEGORY_ALREADY_EXISTS(2025, "Category already exists", HttpStatus.CONFLICT),

    // ===== Follow =====
    ALREADY_FOLLOWING(2026, "Already following this company", HttpStatus.CONFLICT),
    NOT_FOLLOWING(2027, "Not following this company", HttpStatus.BAD_REQUEST),
    // ===== Saved Job =====
    ALREADY_SAVED_JOB(2031, "Job already saved", HttpStatus.CONFLICT),
    NOT_SAVED_JOB(2032, "Job not saved yet", HttpStatus.BAD_REQUEST),
    CERTIFICATE_NOT_FOUND(2033, "Certificate not found", HttpStatus.NOT_FOUND),
    INACTIVE_JOB(2038, "Inactive job", HttpStatus.CONFLICT ),
    INVALID_DATE_RANGE(2040, "Start date must be before end date", HttpStatus.BAD_REQUEST),

    // ===== Review =====
    REVIEW_NOT_FOUND(2036, "Review not found", HttpStatus.NOT_FOUND),
    REVIEW_ALREADY_EXISTS(2037, "You have already reviewed this company", HttpStatus.CONFLICT),

    // ===== Notification =====
    NOTIFICATION_NOT_FOUND(2028, "Notification not found", HttpStatus.NOT_FOUND),

    // ===== ATS =====
    ATS_CV_EMPTY(2041, "CV is empty or unreadable — only text-based PDF/DOCX are supported", HttpStatus.BAD_REQUEST),
    ATS_CV_TOO_LARGE(2042, "CV exceeds maximum allowed size", HttpStatus.BAD_REQUEST),
    ATS_PROVIDER_ERROR(2043, "AI scoring service is temporarily unavailable, please try again later", HttpStatus.SERVICE_UNAVAILABLE),
    ATS_MISSING_INPUT(2044, "Either jobId or jdText must be provided", HttpStatus.BAD_REQUEST),

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
