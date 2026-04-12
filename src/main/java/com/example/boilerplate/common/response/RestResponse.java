package com.example.boilerplate.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL) // Bỏ qua field null thì serialize
public record RestResponse<T>(
        int status,
        boolean success,
        String message,
        T data,
        Instant timestamp
) {
    // Success and has data
    public static <T> RestResponse<T> ok(T data) {
        return new RestResponse<>(200, true, null, data, Instant.now());
    }

    // Success and has data + message
    public static <T> RestResponse<T> okWithMessage(T data, String message) {
        return new RestResponse<>(200, true, message, data, Instant.now());
    }

    // Success and no data (example: delete)
    public static <T> RestResponse<T> okWithMessage(String message) {
        return new RestResponse<>(200, true, message, null, Instant.now());
    }
}
