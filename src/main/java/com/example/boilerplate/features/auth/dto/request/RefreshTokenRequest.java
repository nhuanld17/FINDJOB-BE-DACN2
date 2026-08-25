package com.example.boilerplate.features.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO cho mobile gửi refreshToken trong request body.
 * 
 * Web dùng cookie HttpOnly: {@code @CookieValue("refreshToken")}
 * Mobile (React Native) không có cookie nên gửi trong body:
 *   POST /api/v1/auth/refresh-token
 *   { "refreshToken": "eyJhbGciOi..." }
 */
public record RefreshTokenRequest(
        @JsonProperty("refreshToken")
        String refreshToken
) {
}
