package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.infrastructure.redis.RedisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final RedisService redisService;

    @Value("${app.oauth2.redirect-url:http://localhost:5173/oauth-callback}")
    private String frontendRedirectUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException {

        // STEP 1: Lấy userId từ CustomOidcUser
        CustomOidcUser oidcUser = (CustomOidcUser) authentication.getPrincipal();
        Long userId = oidcUser.getUserId();

        // STEP 2: Tạo one-time ticket
        String ticket = UUID.randomUUID().toString();

        // STEP 3: Lưu ticket vào Redis (TTL 60s, single-use, xóa sau khi đọc)
        //   Format: "oauth2:ticket:<uuid>" → userId
        redisService.set(
                "oauth2:ticket:" + ticket,
                userId.toString(),
                60,
                TimeUnit.SECONDS
        );

        // Fix #7: Log chỉ 8 ký tự đầu ticket — KHÔNG log nguyên credential
        log.info("OIDC login success: userId={}, ticket={}", userId, ticket.substring(0, 8));

        // STEP 4: Redirect về frontend với ticket trên URL
        String redirectUrl = frontendRedirectUrl + "?ticket=" + ticket;
        response.sendRedirect(redirectUrl);

    }
}
