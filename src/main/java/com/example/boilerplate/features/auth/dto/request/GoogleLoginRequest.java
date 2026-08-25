package com.example.boilerplate.features.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO cho mobile gửi Google accessToken lên backend.
 * 
 * Mobile dùng expo-auth-session (PKCE) để lấy accessToken từ Google,
 * sau đó gửi lên backend để exchange lấy JWT của ứng dụng.
 * Backend verify token với Google API, tìm/tạo user, trả về JWT.
 */
public record GoogleLoginRequest(
        @NotBlank(message = "Google access token is required")
        @JsonProperty("googleAccessToken")
        String googleAccessToken
) {
}
