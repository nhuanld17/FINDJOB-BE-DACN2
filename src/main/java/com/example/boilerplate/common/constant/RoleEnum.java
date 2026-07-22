package com.example.boilerplate.common.constant;

public enum RoleEnum {
    USER,
    ADMIN,
    COMPANY;

    public String getAuthority() {
        return "ROLE_" + name();
    }
}
