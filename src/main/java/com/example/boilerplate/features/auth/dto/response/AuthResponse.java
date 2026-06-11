package com.example.boilerplate.features.auth.dto.response;

import com.example.boilerplate.features.user.entity.Role;

import java.util.Set;

public record AuthResponse(
    Long id,
    String username,
    Set<Role> roles,
    String accessToken
) {
}


