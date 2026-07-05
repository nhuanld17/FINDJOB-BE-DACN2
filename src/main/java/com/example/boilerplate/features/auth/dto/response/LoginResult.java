package com.example.boilerplate.features.auth.dto.response;

/**
 * Kết quả trả về của hàm login. Có 2 trường hợp:
 * - {@link AuthResponse}: tài khoản đã active -> đăng nhập thành công (có accessToken).
 * - {@link LoginInactiveResponse}: tài khoản chưa active -> cần verify OTP (mang code 4002/4003/4004).
 *
 * Cả 2 đều trả về HTTP 200. FE phân biệt bằng field `code` (hoặc sự hiện diện của `accessToken`).
 */
public sealed interface LoginResult permits AuthResponse, LoginInactiveResponse {
}
