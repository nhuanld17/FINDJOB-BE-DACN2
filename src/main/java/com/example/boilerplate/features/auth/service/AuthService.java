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
}
