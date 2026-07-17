package com.example.boilerplate.features.auth.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.util.RequestUtils;
import com.example.boilerplate.features.auth.dto.request.ExchangeTicketRequest;
import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.*;
import com.example.boilerplate.features.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RequestUtils requestUtils;

    @PostMapping("/register")
    public ResponseEntity<APIResponse<RegisterResponse>> register(
            @RequestBody @Valid RegisterRequest request,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String pendingToken
    ) {

        RegisterResponse response = authService.register(request, httpServletResponse, pendingToken);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<APIResponse<VerifyOtpResponse>> verifyOtp(
            @RequestBody @Valid VerifyOtpRequest request,
            @CookieValue(name = "pendingToken", required = false) String pendingToken,
            HttpServletResponse httpServletResponse
    ){

        VerifyOtpResponse response = authService.verifyOtp(request, pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<APIResponse<ResendOtpResponse>> resendOtp(
            @CookieValue(name = "pendingToken", required = false) String pendingToken,
            HttpServletResponse httpServletResponse
    ) {

        ResendOtpResponse response = authService.resendOtp(pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletRequest  httpServletRequest,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String pendingToken
    ) {
        LoginResult response = authService.login(request, httpServletRequest, httpServletResponse, pendingToken);

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
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse
    ) {
        authService.logout(refreshToken, httpServletRequest, httpServletResponse);
        return ResponseEntity.ok(APIResponse.success());
    }

    @PostMapping("/exchange-ticket")
    public ResponseEntity<APIResponse<AuthResponse>> exchangeTicket(
            @RequestBody @Valid ExchangeTicketRequest request,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse
    ) {
        AuthResponse response = authService.exchangeTicket(
                request.ticket(),
                requestUtils.getClientIp(httpServletRequest),
                requestUtils.getUserAgent(httpServletRequest),
                httpServletResponse
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }
}
