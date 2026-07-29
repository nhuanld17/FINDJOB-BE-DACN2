package com.example.boilerplate.features.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO cho mobile gửi refreshToken trong request body.
 * <p>
 * Web dùng cookie HttpOnly: {@code @CookieValue("refreshToken")}
 * Mobile (React Native) không có cookie nên gửi trong body:
 * <pre>{@code
 * POST /api/v1/auth/refresh-token
 * { "refreshToken": "eyJhbGciOi..." }
 * }</pre>
 */
public record RefreshTokenRequest(
        @JsonProperty("refreshToken")
        String refreshToken
) {
}
