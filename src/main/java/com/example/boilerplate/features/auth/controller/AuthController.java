package com.example.boilerplate.features.auth.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.AuthResponse;
import com.example.boilerplate.features.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody @Valid RegisterRequest request,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String pendingToken
    ) {

        authService.register(request, httpServletResponse, pendingToken);

        return ResponseEntity.ok(APIResponse.success());
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(
            @RequestBody @Valid VerifyOtpRequest request,
            @CookieValue(name = "pendingToken", required = false) String pendingToken,
            HttpServletResponse httpServletResponse
    ){

        authService.verifyOtp(request, pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success());
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(
            @CookieValue(name = "pendingToken", required = false) String pendingToken,
            HttpServletResponse httpServletResponse
    ) {

        authService.resendOtp(pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String pendingToken
    ) {
        AuthResponse response = authService.login(request, httpServletResponse, pendingToken);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse httpServletResponse
    ) {

        AuthResponse authResponse = authService.refreshToken(refreshToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(authResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse httpServletResponse
    ) {
        authService.logout(refreshToken, httpServletResponse);
        return ResponseEntity.ok(APIResponse.success());
    }
}
