package com.example.boilerplate.features.auth.service;

import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {

    void register(RegisterRequest request, HttpServletResponse response, String pendingToken);

    void verifyOtp(VerifyOtpRequest request, String pendingToken, HttpServletResponse httpServletResponse);

    void resendOtp(String pendingToken, HttpServletResponse httpServletResponse);

    AuthResponse login(LoginRequest request, HttpServletResponse httpServletResponse, String pendingToken);

    AuthResponse refreshToken(String refreshToken, HttpServletResponse httpServletResponse);

    void logout(String refreshToken, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse);
}
