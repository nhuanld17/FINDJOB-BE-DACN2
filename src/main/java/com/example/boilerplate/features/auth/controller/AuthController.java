package com.example.boilerplate.features.auth.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.util.RequestUtils;
import com.example.boilerplate.features.auth.dto.request.*;
import com.example.boilerplate.features.auth.dto.response.*;
import com.example.boilerplate.features.auth.service.AuthService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
            @CookieValue(name = "pendingToken", required = false) String cookiePendingToken,
            @RequestHeader(value = "X-Pending-Token", required = false) String headerPendingToken
    ) {
        // Dual mode: mobile gửi trong header X-Pending-Token, web gửi trong cookie
        String pendingToken = StringUtils.hasText(headerPendingToken) ? headerPendingToken : cookiePendingToken;

        RegisterResponse response = authService.register(request, httpServletResponse, pendingToken);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<APIResponse<VerifyOtpResponse>> verifyOtp(
            @RequestBody @Valid VerifyOtpRequest request,
            @CookieValue(name = "pendingToken", required = false) String cookiePendingToken,
            @RequestHeader(value = "X-Pending-Token", required = false) String headerPendingToken,
            HttpServletResponse httpServletResponse
    ){
        // Dual mode: mobile gửi trong header X-Pending-Token, web gửi trong cookie
        String pendingToken = StringUtils.hasText(headerPendingToken) ? headerPendingToken : cookiePendingToken;

        VerifyOtpResponse response = authService.verifyOtp(request, pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<APIResponse<ResendOtpResponse>> resendOtp(
            @CookieValue(name = "pendingToken", required = false) String cookiePendingToken,
            @RequestHeader(value = "X-Pending-Token", required = false) String headerPendingToken,
            HttpServletResponse httpServletResponse
    ) {
        // Dual mode: mobile gửi trong header X-Pending-Token, web gửi trong cookie
        String pendingToken = StringUtils.hasText(headerPendingToken) ? headerPendingToken : cookiePendingToken;

        ResendOtpResponse response = authService.resendOtp(pendingToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody @Valid LoginRequest request,
            HttpServletRequest  httpServletRequest,
            HttpServletResponse httpServletResponse,
            @CookieValue(name = "pendingToken", required = false) String cookiePendingToken,
            @RequestHeader(value = "X-Pending-Token", required = false) String headerPendingToken
    ) {
        // Dual mode: mobile gửi trong header X-Pending-Token, web gửi trong cookie
        String pendingToken = StringUtils.hasText(headerPendingToken) ? headerPendingToken : cookiePendingToken;

        LoginResult response = authService.login(request, httpServletRequest, httpServletResponse, pendingToken);

        return ResponseEntity.ok(APIResponse.success(response));
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(
            @CookieValue(name = "refreshToken", required = false) String cookieToken,
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletResponse httpServletResponse
    ) {
        // Dual mode: mobile gửi refresh token trong body, web gửi trong cookie
        String refreshToken = body != null && StringUtils.hasText(body.refreshToken())
                ? body.refreshToken()
                : cookieToken;

        AuthResponse authResponse = authService.refreshToken(refreshToken, httpServletResponse);

        return ResponseEntity.ok(APIResponse.success(authResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = "refreshToken", required = false) String cookieToken,
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse
    ) {
        // Dual mode: mobile gửi trong body, web gửi trong cookie
        String refreshToken = body != null && StringUtils.hasText(body.refreshToken())
                ? body.refreshToken()
                : cookieToken;

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

    /**
     * Google Login — Mobile dùng accessToken từ expo-auth-session để đăng nhập.
     * <p>
     * <b>DEPRECATED:</b> Chuyển sang dùng {@link #exchangeTicket(ExchangeTicketRequest, HttpServletRequest, HttpServletResponse)}.
     * Endpoint này gọi Google Token Info API (sắp bị Google shutdown),
     * không hỗ trợ PKCE, dễ bị token substitution nếu không verify aud/azp.
     * Mobile mới nên dùng OIDC flow (exchange-ticket) thay thế.
     *
     * @deprecated Dùng exchangeTicket() thay thế.
     */
    @Deprecated
    @PostMapping("/google-login")
    public ResponseEntity<APIResponse<AuthResponse>> googleLogin(
            @RequestBody @Valid GoogleLoginRequest request,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse
    ) {
        AuthResponse response = authService.googleLogin(
                request.googleAccessToken(),
                requestUtils.getClientIp(httpServletRequest),
                requestUtils.getUserAgent(httpServletRequest),
                httpServletResponse
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Đổi mật khẩu tài khoản.
     * Yêu cầu xác thực (phải đăng nhập).
     * Sau khi đổi thành công: thu hồi tất cả session của thiết bị khác
     * (trừ thiết bị hiện tại) — buộc đăng nhập lại.
     */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpServletRequest
    ) {
        authService.changePassword(
                userDetails,
                request.oldPassword(),
                request.newPassword(),
                httpServletRequest
        );
        return ResponseEntity.ok(APIResponse.success());
    }
}
