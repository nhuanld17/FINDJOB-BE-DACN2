package com.example.boilerplate.common.constant;

/**
 * Phương thức tạo tài khoản
 * - LOCAL: Login truyền thống dùng username/password
 * - GOOGLE: Login bằng OIDC Google
 * - GITHUB: Login băng OIDC Github
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE,
    GITHUB
}
