package com.example.boilerplate.common.constant;

public enum RoleEnum {
    ROLE_USER,
    ROLE_ADMIN;

    public String getAuthority() {
        return name();
    }
}
