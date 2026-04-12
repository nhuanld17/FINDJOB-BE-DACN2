package com.example.boilerplate.common.constant;

public enum RoleEnum {
    USER("ROLE_USER"),
    ADMIN("ROLE_ADMIN");

    private final String authority;

    RoleEnum(final String authority) {
        this.authority = authority;
    }

    public String getAuthority() {
        return this.authority;
    }
}
