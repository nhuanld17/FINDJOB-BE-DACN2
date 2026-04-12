package com.example.boilerplate.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String message,
        Map<String, List<String>> errors,
        Instant timestamp
) {
    // Simple error: 401, 403, 404, 500...
    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, message, null, Instant.now());
    }

    // Validation Error: 400 with detail each field
    public static ErrorResponse ofValidation(Map<String, List<String>> errors) {
        return new ErrorResponse(400, "Validation failed", errors, Instant.now());
    }
}
