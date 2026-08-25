package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.constant.Oauth2Constant;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OidcLoginSuccessHandler — Xử lý sau khi Google xác thực OIDC thành công
 *
 * Vai trò:
 * Sau khi {@link CustomOidcUserService#loadUser} tìm/tạo user thành công và
 * authentication được thiết lập, class này chịu trách nhiệm:
 * 
 *   - Tạo one-time ticket (UUID ngẫu nhiên)
 *   - Lưu ticket vào Redis với TTL 60s — dùng để exchange sang JWT
 *   - Redirect browser/user về FE (web) hoặc app (mobile) kèm ticket
 * 
 *
 * Ticket flow (Backend → Frontend):
 * 
 * Google login success
 *      ↓
 * onAuthenticationSuccess()
 *      ↓
 * Tạo ticket = UUID.randomUUID()
 * Lưu Redis: oauth2:ticket:{ticket} → userId (TTL 60s)
 *      ↓
 * Redirect về FE: /oauth-callback?ticket=xxx
 *      ↓
 * FE gọi: POST /api/v1/auth/exchange-ticket { ticket }
 *      ↓
 * Backend lấy userId từ Redis, tạo JWT access + refresh token
 * 
 *
 * Điểm đặc biệt: hỗ trợ cả Web lẫn Mobile
 * 
 *   - Web: redirect về {@code {frontendRedirectUrl}?ticket=xxx}
 *       (VD: {@code http://localhost:5173/oauth-callback?ticket=xxx})
 *   - Mobile: Mobile truyền {@code return_url} từ lúc gọi OIDC (VD: {@code findjob://oauth/callback})
 *       → lưu ở Redis key {@code oauth2:return:{state}} → đọc ra và redirect về đó
 *   
 *   - Có whitelist scheme ({@link #allowedMobileSchemes}) để chặn open redirect attack:
 *       chỉ cho redirect về scheme đã cấu hình (VD: {@code findjob://})
 * 
 *
 * Bảo mật:
 * 
 *   - One-time ticket: Dùng {@code GETDEL} (đọc + xóa 1 lệnh) → chỉ dùng được 1 lần,
 *       tránh replay attack
 *   - TTL 60s: Ticket tự hủy sau 60 giây — hạn chế cửa sổ tấn công
 *   - Whitelist scheme: Chặn attacker chèn URL redirect tùy ý (open redirect)
 *   - Không log nguyên ticket: Chỉ log 8 ký tự đầu để trace, không lộ credential
 *   - Defense in depth: Validate scheme lần 2 trước khi redirect (phòng Redis bị ghi đè)
 * 
 *
 * Luồng state + return_url (mobile):
 * 
 * Mobile gọi OIDC với ?return_url=findjob://oauth/callback
 *      ↓
 * RedisOAuth2AuthorizationRequestRepository lưu:
 *   oauth2:return:{state-uuid} → "findjob://oauth/callback"
 *      ↓
 * Google redirect về backend callback endpoint
 *      ↓
 * onAuthenticationSuccess() đọc state từ request param
 *      ↓
 * GETDEL oauth2:return:{state} → lấy return_url
 *      ↓
 * Kiểm tra return_url có nằm trong allowedMobileSchemes không
 *      ↓
 * Redirect đến return_url + ?ticket=xxx
 *      ↓
 * App mobile nhận deep link → exchange ticket → lấy JWT
 * 
 *
 * @see AuthenticationSuccessHandler
 * @see CustomOidcUser
 * @see CustomOidcUserService
 * @see com.example.boilerplate.infrastructure.security.RedisOAuth2AuthorizationRequestRepository
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.oauth2.redirect-url:http://localhost:5173/oauth-callback}")
    private String frontendRedirectUrl;

    @Value("${app.oauth2.allowed-mobile-schemes:findjob://}")
    private String allowedMobileSchemes;

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
        //   Dùng StringRedisTemplate để lưu plain text, tránh JSON wrapper
        String redisKey = Oauth2Constant.TICKET_PREFIX + ticket;
        stringRedisTemplate.opsForValue().set(
                redisKey,
                userId.toString(),
                60,
                TimeUnit.SECONDS
        );

        // Fix #7: Log chỉ 8 ký tự đầu ticket — KHÔNG log nguyên credential
        log.info("OIDC login success: userId={}, ticket={}", userId, ticket.substring(0, 8));

        // STEP 4: Xác định đích redirect — mobile (return_url) hoặc web (mặc định)
        // Đọc OAuth "state" từ callback URL — vẫn còn trên request param
        String state = request.getParameter("state");
        String returnUrl = null;
        if (state != null) {
            returnUrl = stringRedisTemplate.opsForValue()
                    .getAndDelete(Oauth2Constant.RETURN_PREFIX + state);
        }

        // Validate whitelist lần 2 (defense in depth — Redis có thể bị ghi từ chỗ khác)
        String target = (returnUrl != null && isAllowedMobileScheme(returnUrl))
                ? returnUrl
                : frontendRedirectUrl;

        String sep = target.contains("?") ? "&" : "?";
        String redirectUrl = target + sep + "ticket=" + ticket;
        response.sendRedirect(redirectUrl);
    }

    private boolean isAllowedMobileScheme(String url) {
        return Arrays.stream(allowedMobileSchemes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(url::startsWith);
    }
}
