package com.example.boilerplate.features.auth.service;

import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.*;
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
}
