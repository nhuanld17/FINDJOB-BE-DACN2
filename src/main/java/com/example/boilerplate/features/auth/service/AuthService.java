package com.example.boilerplate.features.auth.service;

import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.*;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    RegisterResponse register(RegisterRequest request, HttpServletResponse response, String pendingToken);

    VerifyOtpResponse verifyOtp(VerifyOtpRequest request, String pendingToken, HttpServletResponse httpServletResponse);

    ResendOtpResponse resendOtp(String pendingToken, HttpServletResponse httpServletResponse);

    LoginResult login(LoginRequest request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, String pendingToken);

    AuthResponse refreshToken(String refreshToken, HttpServletResponse httpServletResponse);

    void logout(String refreshToken, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse);

    /**
     * Tạo session Redis + AT/RT + set RT cookie cho user.
     * Dùng chung cho login thường, OIDC login, exchange-ticket.
     *
     * @param userId ID của user
     * @param deviceId Device ID (login thường: từ client; OIDC: tự sinh UUID)
     * @param deviceName Tên device (login thường: từ client; OIDC: "Google Login")
     * @param ipAddress IP của request
     * @param userAgent User-Agent string thật từ request (để lưu session)
     * @param httpServletResponse HttpServletResponse để set cookie
     * @return AuthResponse chứa accessToken + user info
     */
    AuthResponse createUserSession(
            Long userId,
            String deviceId,
            String deviceName,
            String ipAddress,
            String userAgent,
            HttpServletResponse httpServletResponse
    );

    AuthResponse exchangeTicket(String ticket,
                                String clientIp,
                                String userAgent,
                                HttpServletResponse httpServletResponse);

    /**
     * Google login cho mobile (Android).
     * Mobile dùng expo-auth-session (PKCE) lấy Google accessToken,
     * gửi lên backend để verify + tạo/tìm user + trả JWT.
     *
     * @param googleAccessToken Google access token từ expo-auth-session
     * @param clientIp IP của request
     * @param userAgent User-Agent string
     * @param httpServletResponse HttpServletResponse để set cookie
     * @return AuthResponse chứa accessToken + refreshToken + user info
     * @deprecated Sử dụng {@link #exchangeTicket(String, String, String, HttpServletResponse)} thay thế.
     *             Endpoint này gọi Google Token Info API (đang bị Google deprecate),
     *             và không hỗ trợ PKCE flow. Giữ lại chỉ để tham khảo / backup.
     *             Mobile đã chuyển sang exchange-ticket flow (OIDC + PKCE).
     */
    @Deprecated
    AuthResponse googleLogin(String googleAccessToken,
                             String clientIp,
                             String userAgent,
                             HttpServletResponse httpServletResponse);

    /**
     * Đổi mật khẩu cho user hiện tại.
     *
     * @param userId       ID của user (từ JWT)
     * @param oldPassword  Mật khẩu cũ — phải khớp với DB
     * @param newPassword  Mật khẩu mới (min 8 ký tự)
     */
    /**
     * Đổi mật khẩu cho user hiện tại.
     * <p>
     * Dùng {@link CustomUserDetails} có sẵn từ SecurityContext (đã load qua JwtAuthFilter)
     * để lấy password hash, tránh query DB lại.
     * Sau khi đổi thành công, thu hồi tất cả session của thiết bị khác
     * (trừ thiết bị đang thực hiện request) — buộc các thiết bị đó đăng nhập lại.
     *
     * @param userDetails  Thông tin user từ JWT (đã có password hash)
     * @param oldPassword  Mật khẩu cũ — phải khớp với hash trong userDetails
     * @param newPassword  Mật khẩu mới (min 8 ký tự)
     * @param request      HttpServletRequest để lấy Bearer token (lấy current sessionId)
     */
    void changePassword(CustomUserDetails userDetails, String oldPassword, String newPassword, HttpServletRequest request);
}
