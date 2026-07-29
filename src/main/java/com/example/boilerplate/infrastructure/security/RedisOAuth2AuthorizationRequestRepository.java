package com.example.boilerplate.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lưu trữ {@link OAuth2AuthorizationRequest} vào Redis, chỉ ghi UUID vào cookie.
 *
 * <p>So với {@code HttpSessionOAuth2AuthorizationRequestRepository} mặc định:
 * <ul>
 *   <li>Hoạt động với {@code SessionCreationPolicy.STATELESS}</li>
 *   <li>Không phụ thuộc HTTP Session → lần đầu login OIDC sau restart BE không fail</li>
 *   <li>Tương thích với Android (React Native) — mobile app gửi cookie qua thư viện</li>
 *   <li>Redis key tồn tại ngay cả khi restart BE</li>
 * </ul>
 *
 * <p>Constructor explicit (không Lombok) để đảm bảo {@code @Qualifier} hoạt động chính xác,
 * inject đúng {@code RedisTemplate} dùng JDK serialization thay vì Jackson JSON.
 */
@Slf4j
@Component
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_state";
    private static final String REDIS_KEY_PREFIX = "oauth2:state:";

    /**
     * Prefix lưu return_url của mobile (keyed by OAuth 'state' param, KHÔNG phải stateId UUID).
     * Xem PLAN_MOBILE_OAUTH.md mục 2 để hiểu rõ sự khác biệt giữa 2 loại "state".
     */
    private static final String RETURN_KEY_PREFIX = "oauth2:return:";

    private static final int STATE_TTL_SECONDS = 120;

    private final RedisTemplate<String, OAuth2AuthorizationRequest> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.oauth2.allowed-mobile-schemes:findjob://}")
    private String allowedMobileSchemes;

    @Autowired
    public RedisOAuth2AuthorizationRequestRepository(
            @Qualifier("oauth2StateRedisTemplate")
            RedisTemplate<String, OAuth2AuthorizationRequest> redisTemplate,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String stateId = getStateIdFromCookie(request);
        if (stateId == null) {
            log.warn("OAuth2: Cookie '{}' not found in callback request — first login may fail", COOKIE_NAME);
            return null;
        }

        OAuth2AuthorizationRequest loaded = redisTemplate.opsForValue()
                .get(REDIS_KEY_PREFIX + stateId);

        if (loaded == null) {
            log.warn("OAuth2: No authorization request in Redis for key '{}' — state may have expired (TTL={}s) or wrong RedisTemplate is being used",
                    REDIS_KEY_PREFIX + stateId, STATE_TTL_SECONDS);
        }

        return loaded;
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }

        String stateId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + stateId,
                authorizationRequest,
                STATE_TTL_SECONDS,
                TimeUnit.SECONDS
        );

        writeCookie(request, response, stateId, STATE_TTL_SECONDS);

        // Mobile OAuth: nếu có return_url hợp lệ, lưu vào Redis keyed by OAuth 'state'
        // ⚠️ Dùng authorizationRequest.getState() — KHÔNG phải stateId UUID (xem plan mục 2)
        String returnUrl = request.getParameter("return_url");
        if (returnUrl != null && !returnUrl.isBlank() && isAllowedMobileScheme(returnUrl)) {
            stringRedisTemplate.opsForValue().set(
                    RETURN_KEY_PREFIX + authorizationRequest.getState(),
                    returnUrl,
                    STATE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        if (loaded != null) {
            removeState(request, response);
        }
        return loaded;
    }

    private void removeState(HttpServletRequest request, HttpServletResponse response) {
        String stateId = getStateIdFromCookie(request);
        if (stateId != null) {
            redisTemplate.delete(REDIS_KEY_PREFIX + stateId);
        }
        writeCookie(request, response, null, 0);
    }

    private String getStateIdFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value != null && !value.isBlank()) ? value : null;
            }
        }
        return null;
    }

    private void writeCookie(HttpServletRequest request, HttpServletResponse response, String value, int maxAge) {
        String safeValue = value != null ? value : "";
        Cookie cookie = new Cookie(COOKIE_NAME, safeValue);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /**
     * Kiểm tra URL có bắt đầu bằng một trong các scheme được whitelist không.
     * Dùng để chống open-redirect: chỉ cho phép redirect về app scheme đã biết.
     */
    private boolean isAllowedMobileScheme(String url) {
        return Arrays.stream(allowedMobileSchemes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(url::startsWith);
    }
}
