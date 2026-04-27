package com.example.boilerplate.common.constant;

public enum RoleEnum {
    USER,
    ADMIN;

    public String getAuthority() {
        return "ROLE_" + name();
    }
}
