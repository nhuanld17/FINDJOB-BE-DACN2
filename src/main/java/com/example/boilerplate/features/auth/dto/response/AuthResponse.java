package com.example.boilerplate.features.auth.dto.response;

import com.example.boilerplate.features.user.entity.Role;

import java.util.Set;

public record AuthResponse(
    int code,            // SuccessCode.LOGIN_SUCCESS (4001) — dùng chung cho cả login và refresh
    Long id,
    String username,
    Set<Role> roles,
    String accessToken
) implements LoginResult {
}


