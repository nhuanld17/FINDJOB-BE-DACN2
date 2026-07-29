package com.example.boilerplate.features.auth.dto.response;

import com.example.boilerplate.features.user.entity.Role;

import java.util.Set;

public record AuthResponse(
    int code,            // SuccessCode.LOGIN_SUCCESS (4001) — dùng chung cho cả login và refresh
    Long id,
    String username,
    Set<Role> roles,
    String accessToken,
    String refreshToken   // MỚI: null với web (dùng cookie HttpOnly), có giá trị với mobile (lưu Keychain)
) implements LoginResult {
}


