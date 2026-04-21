package com.example.boilerplate.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        Integer code,
        String message,
        Map<String, List<String>> errors,
        Instant timestamp
) {
    // 401, 403, 404, 405... — không có business code
    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, null, message, null, Instant.now());
    }

    // AppException — có business code
    public static ErrorResponse of(int status, int code, String message) {
        return new ErrorResponse(status, code, message, null, Instant.now());
    }

    // Validation
    public static ErrorResponse ofValidation(Map<String, List<String>> errors) {
        return new ErrorResponse(400, null, "Validation failed", errors, Instant.now());
    }
}
