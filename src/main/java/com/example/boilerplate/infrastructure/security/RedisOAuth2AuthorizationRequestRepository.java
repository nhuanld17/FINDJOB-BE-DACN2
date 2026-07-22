package com.example.boilerplate.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lưu trữ {@link OAuth2AuthorizationRequest} vào Redis, chỉ ghi UUID vào cookie.
 *
 * <h3>📌 Vai trò của class này</h3>
 * <p>
 * {@code AuthorizationRequestRepository} là nơi lưu tạm {@code OAuth2AuthorizationRequest}
 * trong suốt OAuth2 Authorization Code flow. Request này chứa các thông tin quan trọng:
 * <ul>
 *   <li><b>state</b> — chống CSRF: khi Google callback, Spring verify state khớp</li>
 *   <li><b>nonce</b> — chống replay attack: verify trong ID token</li>
 *   <li><b>code_challenge / code_verifier</b> — PKCE: chứng minh app gửi request
 *       là app nhận code (S256)</li>
 *   <li><b>clientId, redirectUri, scopes</b> — thông tin cấu hình OAuth2 client</li>
 * </ul>
 * Nếu mất request này giữa bước 1 (redirect Google) và bước 2 (Google callback),
 * toàn bộ OIDC login sẽ thất bại vì không có state để verify + không có
 * code_verifier để trao đổi code lấy token.
 * </p>
 *
 * <h3>❌ Vấn đề với HttpSession mặc định</h3>
 * <p>
 * {@code SecurityConfig} dùng {@code SessionCreationPolicy.STATELESS} vì backend là REST API
 * không cần HTTP Session. Nhưng OAuth2 login flow mặc định dùng
 * {@code HttpSessionOAuth2AuthorizationRequestRepository} — nó lưu request vào HTTP Session.
 * Kết quả: lần đầu gọi OIDC sau restart server chưa có session → request không được lưu
 * → state mất → callback fail. Lần thứ hai mới có session → lúc đó mới hoạt động.
 * </p>
 *
 * <h3>✅ Giải pháp: Redis + Cookie</h3>
 * <p>
 * Class này thay thế cơ chế HttpSession bằng Redis, tách làm 2 phần:
 * <ul>
 *   <li><b>Redis</b> — lưu toàn bộ {@code OAuth2AuthorizationRequest} với key
 *       {@code oauth2:state:{uuid}}, TTL = 120 giây. Dùng {@code RedisTemplate}
 *       (Jackson JSON serialization) — an toàn, không RCE risk.</li>
 *   <li><b>Cookie</b> — chỉ ghi UUID (36 bytes) vào cookie {@code oauth2_state}
 *       với HttpOnly + SameSite=Lax + Secure (tự động theo request).
 *       Cookie là cầu nối để khi Google callback về, server biết UUID nào
 *       để tra Redis.</li>
 * </ul>
 * </p>
 *
 * <h3>🔁 Flow chi tiết</h3>
 * <pre>
 * 1. User click "Login with Google"
 * 2. Spring tạo OAuth2AuthorizationRequest (chứa state, nonce, code_challenge...)
 * 3. saveAuthorizationRequest()
 *    ├── Tạo UUID ngẫu nhiên
 *    ├── Lưu request vào Redis: SET oauth2:state:{uuid} → request (TTL 120s)
 *    └── Ghi UUID vào cookie: SET oauth2_state={uuid} (HttpOnly, Secure, SameSite=Lax)
 * 4. Redirect user → Google Auth URL (kèm ?state=xxx&code_challenge=yyy)
 *
 * 5. Google redirect về callback (kèm ?state=xxx&code=abc)
 * 6. loadAuthorizationRequest()
 *    ├── Đọc UUID từ cookie oauth2_state
 *    └── GET oauth2:state:{uuid} từ Redis → khôi phục request
 * 7. Spring verify state khớp ✅
 * 8. removeAuthorizationRequest()
 *    ├── Xóa Redis key
 *    └── Xóa cookie oauth2_state (maxAge=0)
 * 9. Spring dùng code_verifier từ request + code từ Google → exchange lấy token
 * </pre>
 *
 * <h3>📊 So sánh với các giải pháp khác</h3>
 * <table border="1">
 *   <tr><th>Tiêu chí</th><th>HttpSession (mặc định)</th><th>Cookie (cũ)</th><th>Redis + Cookie (hiện tại)</th></tr>
 *   <tr><td>Hoạt động với STATELESS</td><td>❌ Lần đầu fail</td><td>✅ OK</td><td>✅ OK</td></tr>
 *   <tr><td>Cookie size</td><td>Không dùng cookie</td><td>~2-3KB (cả object)</td><td>36 bytes (chỉ UUID)</td></tr>
 *   <tr><td>Serialization</td><td>Java native</td><td>Java native (RCE risk)</td><td>Jackson JSON (an toàn)</td></tr>
 *   <tr><td>Data tồn tại khi restart</td><td>❌ Mất</td><td>✅ Còn (trong cookie)</td><td>✅ Còn (trong Redis)</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_state";
    private static final String REDIS_KEY_PREFIX = "oauth2:state:";
    private static final int STATE_TTL_SECONDS = 120;

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String stateId = getStateIdFromCookie(request);
        if (stateId == null) return null;

        // Redis GET — lấy OAuth2AuthorizationRequest từ Redis
        return (OAuth2AuthorizationRequest) redisTemplate.opsForValue()
                .get(REDIS_KEY_PREFIX + stateId);
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

        // Tạo UUID mới cho mỗi request
        String stateId = UUID.randomUUID().toString();

        // Lưu vào Redis với TTL 120s
        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + stateId,
                authorizationRequest,
                STATE_TTL_SECONDS,
                TimeUnit.SECONDS
        );

        // Chỉ ghi UUID vào cookie (36 bytes)
        writeCookie(request, response, stateId, STATE_TTL_SECONDS);
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

    // ===== Private helpers =====

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
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure()); // false ở dev (HTTP), true ở production (HTTPS)
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
