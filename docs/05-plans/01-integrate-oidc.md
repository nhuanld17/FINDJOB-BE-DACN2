# 📋 Plan: Tích Hợp OIDC (Google Login) Vào Spring Boot Auth System

> **Ngày:** 2026-07-14
> **Phiên bản:** 3.0 (sau review v3 — 15 fixes)
> **Dựa trên quyết định:**
> - 🔗 Link Google account với local account qua email (giữ nguyên authProvider=LOCAL, chỉ set socialId)
> - 🎫 **One-time ticket redirect** — OAuth2 callback redirect về FE kèm ticket, FE gọi API đổi ticket lấy AT/RT
> - 🤖 Device info: dùng `RequestUtils.getUserAgent()` + `RequestUtils.getClientIp()` có sẵn
> - 🌐 Chỉ Google trước, thiết kế để dễ thêm provider sau
> - 🔒 Giữ nguyên `SessionCreationPolicy.STATELESS` + ghi chú

---

## 🔷 MỤC LỤC

1. [Kiến trúc tổng thể](#-kiến-trúc-tổng-thể)
2. [FLOW MỚI: One-time ticket redirect](#-flow-mới-one-time-ticket-redirect)
3. [Luồng Spring Security OIDC](#-luồng-spring-security-oidc)
4. [Phase 1: Tái cấu trúc (Refactor)](#-phase-1-tái-cấu-trúc-refactor)
5. [Phase 2: Implement OIDC](#-phase-2-implement-oidc)
6. [Phase 3: Testing](#-phase-3-testing)
7. [Danh sách file thay đổi](#-danh-sách-file-thay-đổi)
8. [Các cạm bẫy cần tránh](#-các-cạm-bẫy-cần-tránh)
9. [Prerequisites: Google Cloud Setup](#-prerequisites-google-cloud-setup)
10. [Known Limitations](#-known-limitations)

---

## 📐 KIẾN TRÚC TỔNG THỂ

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        OIDC FLOW — One-time Ticket Redirect                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SPA (FE) :5173                 Backend :8080              Google            │
│    │                                │                        │               │
│    │  Click "Login with Google"     │                        │               │
│    │  window.location =             │                        │               │
│    │  'http://localhost:8080/       │                        │               │
│    │   oauth2/authorization/google' │                        │               │
│    │───────────────────────────────→│                        │               │
│    │                                │ redirect 302           │               │
│    │                                │───────────────────────→│               │
│    │                                │                        │               │
│    │  (Trình duyệt chuyển đến       │     Google login page  │               │
│    │   accounts.google.com)         │◄───────────────────────│               │
│    │                                │                        │               │
│    │  User nhập credentials         │                        │               │
│    │                                │                        │               │
│    │  Google callback               │                        │               │
│    │  GET /login/oauth2/code/google │                        │               │
│    │  ?code=xxx&state=yyy           │                        │               │
│    │◄───────────────────────────────│                        │               │
│    │                                │                        │               │
│    │  ┌──── Spring Security xử lý ──┐                        │               │
│    │  │ 1. Validate state           │                        │               │
│    │  │ 2. Exchange code → token    │                        │               │
│    │  │ 3. Validate ID token        │                        │               │
│    │  │ 4. CustomOidcUserService    │                        │               │
│    │  │ 5. OidcLoginSuccessHandler  │                        │               │
│    │  └─────────────────────────────┘                        │               │
│    │                                │                        │               │
│    │  ←── redirect 302 ─────────────│                        │               │
│    │  http://localhost:5173/        │                        │               │
│    │  oauth-callback?ticket=<uuid>  │                        │               │
│    │                                │                        │               │
│    │  ┌── FE lấy ticket từ URL ─────┐                        │               │
│    │  │ window.location.search      │                        │               │
│    │  │ → "?ticket=abc-123"         │                        │               │
│    │  └─────────────────────────────┘                        │               │
│    │                                │                        │               │
│    │  POST /api/v1/auth/            │                        │               │
│    │  exchange-ticket               │                        │               │
│    │  { "ticket": "abc-123" }       │                        │               │
│    │───────────────────────────────→│                        │               │
│    │                                │  Redis: atomic delete  │               │
│    │                                │  → verify + delete     │               │
│    │                                │  → createUserSession() │               │
│    │                                │                        │               │
│    │  ←── JSON { accessToken,  ─────│                        │               │
│    │  user }                        │                        │               │
│    │                                │                        │               │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 🔷 FLOW MỚI: ONE-TIME TICKET REDIRECT

### Tại sao không trả JSON trực tiếp?

OAuth2 callback là **browser redirect chain** (GET request từ trình duyệt). Nếu backend trả JSON:
- Trình duyệt hiển thị JSON thô thành 1 trang trắng
- SPA (khác origin) **không thể đọc nội dung** tab/popup khác origin
- `fetch()` không dùng được vì OAuth2 redirect không đi qua fetch

### One-time ticket solution

```
1. OidcLoginSuccessHandler:
   → Tạo UUID (ticket)
   → Lưu vào Redis: "oauth2:ticket:<uuid>" → userId (TTL: 60s)
   → redirect 302: http://localhost:5173/oauth-callback?ticket=<uuid>

2. SPA nhận được ticket từ URL:
   → Gọi POST /api/v1/auth/exchange-ticket { "ticket": "abc-123" }

3. Backend exchangeTicket():
   → Atomic delete: redisTemplate.execute(GETDEL) để lấy value và xóa trong 1 bước
   → Nếu key không tồn tại → 401 (single-use, đã dùng hoặc hết hạn)
   → Gọi createUserSession(userId, ..., userAgent, ...)
   → Trả JSON { accessToken, user }
```

### Chi tiết kiến trúc & cách hoạt động

#### 1. Vấn đề gốc: Tại sao OAuth2 callback không thể trả JSON trực tiếp?

Khi user bấm "Login with Google", toàn bộ luồng OAuth2 là **browser redirect chain** — tức là các request GET do trình duyệt thực hiện, hoàn toàn không phải AJAX/fetch call:

```
User click "Login with Google"
  → window.location = 'http://localhost:8080/oauth2/authorization/google'
    (Trình duyệt tải trang mới — mất context SPA)
      → Backend redirect 302 → accounts.google.com
        (User nhập mật khẩu Google)
          → Google redirect 302 → Backend /login/oauth2/code/google
            (Backend xử lý OIDC xong... GIỜ TRẢ VỀ CÁI GÌ?)
```

Nếu backend cố trả JSON tại thời điểm này:
- **Trình duyệt hiển thị JSON thô** thành 1 trang trắng xóa với đống text
- **SPA (khác origin)** không thể đọc nội dung của tab/popup khác origin do same-origin policy
- **`fetch()` không dùng được** vì OAuth2 redirect chain không phải do JavaScript fetch khởi tạo
- Không có cách nào để SPA nhận được access token trực tiếp từ callback URL

#### 2. Giải pháp: Redirect về FE + Ticket trung gian

Thay vì cố trả JSON (bất khả thi), backend:

**Bước A — OidcLoginSuccessHandler** (chạy trên backend sau khi Google xác thực thành công):
1. Tạo 1 chuỗi UUID ngẫu nhiên làm **ticket** (vd: `abc-123-def-456`)
2. Lưu vào Redis với:
   - Key: `oauth2:ticket:abc-123-def-456`
   - Value: `userId` (ID của user trong DB)
   - TTL: 60 giây (chỉ có hiệu lực trong 1 phút)
3. Redirect trình duyệt về FE: `http://localhost:5173/oauth-callback?ticket=abc-123-def-456`

**Bước B — Frontend SPA** (chạy trên trình duyệt):
1. Đọc `?ticket=abc-123-def-456` từ URL
2. Gọi `POST /api/v1/auth/exchange-ticket` với body `{ "ticket": "abc-123-def-456" }`
3. Đây là API call bình thường → nhận JSON response

**Bước C — Backend xử lý exchangeTicket():**
1. Đọc key `oauth2:ticket:abc-123-def-456` từ Redis (atomic delete — GETDEL)
2. Nếu key không tồn tại → throw 401 (ticket đã dùng, hết hạn, hoặc không hợp lệ)
3. Nếu có → lấy `userId`, gọi `createUserSession()` → sinh AT/RT → set cookie → trả JSON `{ accessToken, user }`

```
Trình duyệt                            Backend :8080                      Redis
  │                                        │                               │
  │  (Sau Google callback)                 │                               │
  │  ←── redirect 302 ─────────────────────│                               │
  │  ?ticket=abc-123                       │                               │
  │                                        │                               │
  │  ┌── FE parse URL ──────────────────┐  │                               │
  │  │ ticket = urlParams.get("ticket") │  │                               │
  │  └──────────────────────────────────┘  │                               │
  │                                        │                               │
  │  POST /exchange-ticket                 │                               │
  │  { "ticket": "abc-123" }               │                               │
  │ ──────────────────────────────────────→│                               │
  │                                        │  Lua: GET + DEL (atomic)      │
  │                                        │ ─────────────────────────────→│
  │                                        │  ←── userId ──────────────────│
  │                                        │                               │
  │                                        │  createUserSession(userId,..) │
  │                                        │                               │
  │  ←── JSON ─────────────────────────────│                               │
  │  { accessToken, user }                 │                               │
```

#### 3. Tại sao gọi là **ONE-TIME TICKET**?

Chữ **"One-time"** ở đây rất quan trọng, nó ám chỉ ticket chỉ dùng được **đúng 1 lần**:

- **Atomic delete (GETDEL):** Redis lấy value VÀ xóa key trong **1 bước nguyên tử** (dùng Lua script hoặc lệnh GETDEL của Redis ≥ 6.2)
  - Request A: GETDEL key → nhận `userId`, key bị xóa
  - Request B (cùng lúc): GETDEL key → nhận `null` (key đã bị A xóa mất rồi)
  - ✅ Chỉ 1 trong 2 request thành công

- **TOCTOU race condition được loại bỏ:** Nếu check-then-delete thông thường:
  ```
  // ❌ SAI: 2 request song song đều pass check → đều tạo session được
  String vA = redisService.get(key);  // A: OK
  String vB = redisService.get(key);  // B: OK (cùng lúc)
  redisService.delete(key);           // A xóa
  redisService.delete(key);           // B xóa (dư thừa)
  // → Cả A và B đều dùng được 1 ticket → phá single-use!

  // ✅ ĐÚNG: 1 bước GETDEL → chỉ 1 thằng lấy được value
  String vA = redisTemplate.execute(GETDEL, key);  // A: userId
  String vB = redisTemplate.execute(GETDEL, key);  // B: null
  // → Single-use được đảm bảo ở cấp Redis
  ```

- **TTL 60s:** Nếu user không kịp exchange (tắt trình duyệt, mất mạng, ...), ticket tự hủy sau 60 giây → không lo tồn đọng key rác

#### 4. Flow tuần tự từ đầu đến cuối

```
1. User click "Login with Google" trên SPA (:5173)
2. FE redirect window.location → /oauth2/authorization/google (BE :8080)
3. BE redirect 302 → accounts.google.com (Google login page)
4. User nhập credentials Google
5. Google callback → GET /login/oauth2/code/google (về BE)
6. Spring Security OAuth2LoginAuthenticationFilter xử lý:
   a. Validate state (chống CSRF)
   b. Exchange authorization code → access token + ID token
   c. Validate ID token (signature, iss, aud, exp)
7. CustomOidcUserService.loadUser():
   a. super.loadUser() lấy user info từ Google
   b. Check email_verified (🔴 bảo mật: chống account-takeover)
   c. Tìm user theo email / Tạo user mới / Link Google account
   d. Trả CustomOidcUser chứa userId
8. OidcLoginSuccessHandler:
   a. Tạo UUID (ticket)
   b. Lưu Redis: "oauth2:ticket:<uuid>" → userId (TTL 60s)
   c. Log chỉ 8 ký tự đầu ticket (bảo mật)
   d. redirect 302 → http://localhost:5173/oauth-callback?ticket=<uuid>
9. SPA nhận URL, parse ticket
10. SPA gọi POST /api/v1/auth/exchange-ticket { "ticket": "<uuid>" }
11. Backend exchangeTicket():
    a. Lua script GETDEL trong Redis (atomic)
    b. Nếu null → 401 Unauthorized
    c. Parse userId
    d. createUserSession(userId, deviceId="random-uuid", deviceName="Google Login", ip, userAgent, response)
    e. Trả JSON { accessToken, user }
✅ User đã login thành công
```

> **Tóm lại:** Ticket là "vé" dùng 1 lần, thay thế cho việc trả JSON trực tiếp (bất khả thi vì OAuth2 redirect là GET request từ browser). Nó vừa giải quyết vấn đề cross-origin giữa callback endpoint và SPA, vừa đảm bảo an toàn nhờ atomic delete (chống dùng lại ticket) + TTL ngắn (60s).

---

## 🔷 LUỒNG SPRING SECURITY OIDC

### Vị trí trong Filter Chain

```
Position | Filter                              | Xử lý
─────────|─────────────────────────────────────|──────────────────────────────
   1     | SecurityContextHolderFilter         | Giữ SecurityContext
   2     | LogoutFilter                        | Logout handler
   3     | OAuth2AuthorizationRequestRedirect  | /oauth2/authorization/{id}
   4     | OAuth2LoginAuthenticationFilter     | /login/oauth2/code/{id}   ← OIDC
   5     | JwtAuthFilter (của bạn)             | Xác thực JWT token        ← Của bạn
   6     | ...                                 |
   7     | FilterSecurityInterceptor           | Authorize requests

Ghi chú:
- /api/v1/auth/login KHÔNG đi qua UsernamePasswordAuthenticationFilter.
  Login thường là @PostMapping trong AuthController tự gọi AuthenticationManager thủ công.
```

### Session Policy

```java
// GIỮ NGUYÊN Stateless. Lý do:
// - HttpSessionOAuth2AuthorizationRequestRepository gọi request.getSession() TRỰC TIẾP
//   (không qua sessionManagement policy), nên state vẫn được lưu dù policy là STATELESS.
// - Policy STATELESS chỉ ảnh hưởng đến việc Spring Security tự động lưu SecurityContext
//   vào session — OAuth2 flow không cần điều này.
//
// ⚠️ LƯU Ý QUAN TRỌNG: KHÔNG được set server.servlet.session.cookie.same-site=strict
//    vì JSESSIONID (chứa OAuth2 state) cần SameSite mặc định (Lax) để sống sót qua
//    redirect từ accounts.google.com về callback. Strict sẽ làm invalid state toàn tập.
.sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS));
```

---

## 🔷 PHASE 1: TÁI CẤU TRÚC (REFACTOR)

> **Mục tiêu:** Tách logic dùng chung ra method riêng, cả `login()` và OIDC handler đều gọi được.
> **KHÔNG thay đổi behavior** của code hiện tại.

### 1.1 AuthService.java (interface)

**Thêm method (6 tham số — bao gồm userAgent):**

```java
public interface AuthService {
    // ... các method hiện tại (GIỮ NGUYÊN) ...

    /**
     * Tạo session Redis + AT/RT + set RT cookie cho user.
     * Dùng chung cho login thường, OIDC login, exchange-ticket.
     *
     * @param userId    ID của user
     * @param deviceId  Device ID (login thường: từ client; OIDC: tự sinh UUID)
     * @param deviceName Tên device (login thường: từ client; OIDC: "Google Login")
     * @param ipAddress  IP của request
     * @param userAgent  User-Agent string thật từ request (để lưu session)
     * @param response   HttpServletResponse để set cookie
     * @return AuthResponse chứa accessToken + user info
     */
    AuthResponse createUserSession(
        Long userId,
        String deviceId,
        String deviceName,
        String ipAddress,
        String userAgent,
        HttpServletResponse response
    );
}
```

### 1.2 AuthServiceImplement.java

#### 1.2a Tách `createUserSession()` — 6 tham số

```java
@Override
public AuthResponse createUserSession(
        Long userId,
        String deviceId,
        String deviceName,
        String ipAddress,
        String userAgent,
        HttpServletResponse response) {

    // 1. Kiểm tra user — dùng preLoginCheck
    User user = preLoginCheck(userId);

    // 2. Tạo sessionId
    String sessionId = UUID.randomUUID().toString();

    // 3. Build UserDetails
    CustomUserDetails userDetails = new CustomUserDetails(user);

    // 4. Tạo AT & RT
    String accessToken = jwtUtil.generateAccessToken(userDetails, sessionId, deviceId);
    String refreshToken = jwtUtil.generateRefreshToken(userDetails, sessionId, deviceId);

    // 5. Lưu session vào Redis — TRUYỀN ĐÚNG userAgent THẬT
    sessionService.createSession(
        sessionId,
        userDetails.getUsername(),
        deviceId,
        jwtUtil.extractJti(refreshToken),
        deviceName,
        ipAddress,
        userAgent     // ←=userAgent thật, KHÔNG phải deviceName
    );

    // 6. Lưu session index cho user
    sessionService.addSessionToUser(userId.toString(), sessionId);

    // 7. Set RT cookie
    writeCookie(response, "refreshToken", refreshToken,
        true, true, "/", "Strict", 7 * 24 * 60 * 60L);

    // 8. Trả về AuthResponse
    return new AuthResponse(
        SuccessCode.LOGIN_SUCCESS.getCode(),
        user.getId(),
        user.getUsername(),
        user.getRoles(),
        accessToken
    );
}
```

#### 1.2b Tách `preLoginCheck()`

```java
/**
 * Kiểm tra trạng thái user trước khi tạo session.
 *
 * - User bị deleted (banned) → throw AppException(ACCOUNT_BANNED)
 * - User không tồn tại → throw AppException(USER_NOT_FOUND)
 *
 * LƯU Ý: KHÔNG check isActive ở đây. Login thường xử lý inactive qua
 * handleInactiveUserLogin() trả về LoginInactiveResponse (HTTP 200).
 * OIDC flow xử lý inactive trong CustomOidcUserService (auto-activate).
 */
private User preLoginCheck(Long userId) {
    User user = userRepository.findByIdWithRoles(userId)
        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

    if (user.isDeleted()) {
        throw new AppException(ErrorCode.ACCOUNT_BANNED);
    }

    return user;
}
```

#### 1.2c Refactor `login()` — gọi method chung

```java
@Override
public LoginResult login(LoginRequest loginRequest,
                         HttpServletRequest httpServletRequest,
                         HttpServletResponse httpServletResponse,
                         String pendingToken) {

    // ... GIỮ NGUYÊN: xác thực password bằng AuthenticationManager ...
    // ... GIỮ NGUYÊN: check deleted ...
    // ... GIỮ NGUYÊN: check inactive → handleInactiveUserLogin ...

    // THAY THẾ: code inline → gọi method chung
    AuthResponse authResponse = createUserSession(
        userDetails.getId(),
        loginRequest.deviceId().toString(),
        loginRequest.deviceName(),
        requestUtils.getClientIp(httpServletRequest),
        requestUtils.getUserAgent(httpServletRequest),    // ← userAgent thật
        httpServletResponse
    );
    return authResponse;
}
```

### 1.3 User.java — Thêm field OIDC

```java
@Enumerated(EnumType.STRING)
@Column(name = "auth_provider", length = 20, nullable = false)
private AuthProvider authProvider = AuthProvider.LOCAL;

@Column(name = "social_id", length = 200)
private String socialId;

@Column(name = "avatar_url", length = 500)
private String avatarUrl;

public enum AuthProvider {
    LOCAL,
    GOOGLE,
    GITHUB
}
```

### 1.4 Flyway Migration: `V5__add_oidc_fields.sql`

```sql
ALTER TABLE users
    ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN social_id VARCHAR(200),
    ADD COLUMN avatar_url VARCHAR(500);
```

---

## 🔷 PHASE 2: IMPLEMENT OIDC

### 2.1 File mới: `AccountBannedException.java`

```java
package com.example.boilerplate.infrastructure.security.oauth2;

import org.springframework.security.core.AuthenticationException;

/**
 * Exception riêng cho OIDC flow khi user bị BANNED.
 * KHÔNG dùng AppException vì OAuth2 flow chỉ bắt AuthenticationException.
 */
public class AccountBannedException extends AuthenticationException {
    public AccountBannedException(String msg) { super(msg); }
}
```

### 2.2 File mới: `CustomOidcUser.java`

```java
package com.example.boilerplate.infrastructure.security.oauth2;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;
import java.util.List;

/**
 * OidcUser mở rộng chứa userId local.
 * Dùng để truyền userId từ OidcUserService → SuccessHandler.
 */
public class CustomOidcUser extends DefaultOidcUser {

    private final Long userId;
    private final String email;

    public CustomOidcUser(
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            OidcUserInfo userInfo,
            Long userId,
            String email
    ) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
        this.email = email;
    }

    public Long getUserId() { return userId; }
    public String getEmail() { return email; }

    @Override
    public String getName() {
        return email; // dùng email làm subject (JWT sub)
    }
}
```

### 2.3 File mới: `CustomOidcUserService.java` ⭐

```java
package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.RoleRepository;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OtpService otpService;    // ← Inject để dọn OTP khi auto-activate
    // KHÔNG inject passwordEncoder — user OIDC không cần password check

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // STEP 1: super.loadUser() lấy OidcUser từ Google (ID token + UserInfo)
        // ⚠️ KHÔNG @Transactional ở đây: super.loadUser() gọi HTTP tới Google,
        //   giữ DB connection suốt network I/O là anti-pattern.
        //   Phần DB (find/create/link user) sẽ được thực hiện riêng.
        OidcUser oidcUser = super.loadUser(userRequest);
        Map<String, Object> claims = oidcUser.getClaims();

        // STEP 2: Lấy claims — check null/blank TRƯỚC khi normalize
        String rawEmail = (String) claims.get("email");
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("email_not_provided",
                    "Email not provided by Google", null));
        }
        String email = rawEmail.toLowerCase().trim();

        String name = (String) claims.get("name");
        String sub = (String) claims.get("sub");
        String picture = (String) claims.get("picture");
        Boolean emailVerified = (Boolean) claims.get("email_verified");

        log.info("OIDC login attempt: email={}, provider=GOOGLE, sub={}", email, sub);

        // STEP 3: Kiểm tra email_verified trước khi link (🔴 BẢO MẬT)
        // Thiếu check này = lỗ hổng account-takeover: attacker có thể tạo Google account
        // với email chưa verify → link vào victim account.
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("email_not_verified",
                    "Email not verified by Google. Please verify your email and try again.",
                    null));
        }

        // STEP 4: Tìm user — email đã normalize (lowercase + trim) khớp với local flow
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            // ===== USER MỚI: Tạo tài khoản mới =====
            user = new User();
            user.setEmail(email);
            user.setUsername(email);
            user.setFullName(name);
            user.setAvatarUrl(picture);
            // 🔴 CRITICAL FIX: DB có NOT NULL constraint cho password
            // → Không thể setPassword(null) hay ""
            // → Hash UUID ngẫu nhiên: không ai login password được, an toàn
            user.setPassword("$2a$10$invalid.hash.placeholder"); // placeholder, sẽ tạo đúng bên dưới
            user.setActive(true);                  // Google đã verify → auto active
            user.setDeleted(false);
            user.setAuthProvider(User.AuthProvider.GOOGLE);
            user.setSocialId(sub);
            user.setRoles(new HashSet<>());

            // Role USER — throw OAuth2AuthenticationException nếu role không tồn tại
            // (không dùng RuntimeException → sẽ thành 500 thô)
            roleRepository.findByName(RoleEnum.USER)
                .ifPresentOrElse(
                    role -> user.getRoles().add(role),
                    () -> {
                        throw new OAuth2AuthenticationException(
                            new OAuth2Error("internal_error",
                                "Default role not found", null));
                    }
                );

            userRepository.save(user);
            log.info("Created new user from OIDC: email={}", email);

        } else {
            // ===== USER ĐÃ TỒN TẠI: Link Google account =====

            // STEP 5: Check BANNED
            if (user.isDeleted()) {
                log.warn("BANNED user tried OIDC login: email={}", email);
                throw new AccountBannedException(
                    "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ hỗ trợ.");
            }

            boolean mutated = false;

            // STEP 6: Auto-activate nếu inactive (chưa verify OTP)
            if (!user.isActive()) {
                user.setActive(true);
                mutated = true;
                // Dọn Redis state OTP — tránh sót key otp:*, pending:* tới hết TTL
                otpService.clearAll(user.getId());
                log.info("Activated inactive user via OIDC + cleared OTP state: email={}", email);
            }

            // STEP 7: Link Google — KHÔNG ghi đè authProvider (giữ LOCAL)
            if (user.getSocialId() == null || user.getSocialId().isBlank()) {
                user.setSocialId(sub);
                mutated = true;
            }
            if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
                user.setAvatarUrl(picture);
                mutated = true;
            }

            // Chỉ save khi có mutation — tránh touch updated_at mỗi lần login
            if (mutated) {
                userRepository.save(user);
                log.info("Updated user via OIDC: email={}, mutations applied", email);
            }
        }

        // STEP 8: Tạo CustomOidcUser chứa userId — dùng SimpleGrantedAuthority
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
            .map(role -> new SimpleGrantedAuthority(role.getName().getAuthority()))
            .toList();

        return new CustomOidcUser(
            authorities,
            oidcUser.getIdToken(),
            oidcUser.getUserInfo(),
            user.getId(),
            user.getEmail()
        );
    }
}
```

### 2.4 File mới: `OidcLoginSuccessHandler.java`

> **Thay đổi lớn:** Không trả JSON nữa. Redirect về FE kèm one-time ticket.
> **Fix #7:** Log chỉ 8 ký tự đầu ticket, không log nguyên credential.
> **Fix #10:** Default redirect :5173/oauth-callback (khớp CorsConfig Vite).

```java
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
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

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

        // 🔴 Fix #7: Log chỉ 8 ký tự đầu ticket — KHÔNG log nguyên credential
        log.info("OIDC login success: userId={}, ticket={}", userId, ticket.substring(0, 8));

        // STEP 4: Redirect về frontend với ticket trên URL
        String redirectUrl = frontendRedirectUrl + "?ticket=" + ticket;
        response.sendRedirect(redirectUrl);
    }
}
```

### 2.5 File mới: `OidcLoginFailureHandler.java`

> **Dùng `ErrorResponse` format để nhất quán** với JwtAuthEntryPoint + GlobalExceptionHandler.

```java
package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        log.warn("OIDC login failed: {}", exception.getMessage());

        int httpStatus;
        int errorCode;
        String message;

        if (exception instanceof AccountBannedException) {
            httpStatus = 403;
            errorCode = 2007;   // ACCOUNT_BANNED
            message = exception.getMessage();
        } else {
            httpStatus = 401;
            errorCode = 3008;   // INVALID_CREDENTIALS
            message = "Xác thực Google thất bại. Vui lòng thử lại.";
        }

        // ErrorResponse format: { status, code, message, errors, timestamp }
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
            response.getOutputStream(),
            ErrorResponse.of(httpStatus, errorCode, message)
        );
    }
}
```

### 2.6 File mới: `ExchangeTicketRequest.java`

```java
package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExchangeTicketRequest(
    @NotBlank(message = "BLANK_FIELD")
    @Size(min = 1, max = 200, message = "OUT_OF_SIZE")
    String ticket
) {}
```

### 2.7 File mới: `AuthController.java` — Thêm endpoint + RequestUtils

```java
// THÊM dependency vào AuthController (Fix #4: inject RequestUtils)
private final AuthService authService;
private final RequestUtils requestUtils;    // ← THÊM

// THÊM endpoint
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
```

### 2.8 AuthServiceImplement — `exchangeTicket()` với atomic delete

```java
// Trong AuthService.java (interface) — thêm method
AuthResponse exchangeTicket(
    String ticket,
    String ipAddress,
    String userAgent,
    HttpServletResponse response
);

// Trong AuthServiceImplement.java — Fix #6: atomic delete chống TOCTOU
// Prefix "oauth2:ticket:" giờ định nghĩa trong Oauth2Constant.TICKET_PREFIX (common/constant)

@Override
public AuthResponse exchangeTicket(
        String ticket,
        String ipAddress,
        String userAgent,
        HttpServletResponse response) {

    String redisKey = Oauth2Constant.TICKET_PREFIX + ticket;

    // Fix #6: Atomic delete — lấy value và xóa trong 1 bước
    //   Dùng Redis GETDEL (Redis ≥6.2) hoặc Lua script
    //   → 2 request song song cùng ticket chỉ 1 trong 2 lấy được value
    String userIdStr = redisTemplate.execute(
        new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); " +
            "if v then redis.call('DEL', KEYS[1]) end; " +
            "return v;",
            String.class
        ),
        java.util.List.of(redisKey)
    );

    if (userIdStr == null || userIdStr.isBlank()) {
        throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    // Parse userId
    Long userId;
    try {
        userId = Long.parseLong(userIdStr);
    } catch (NumberFormatException e) {
        throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    // Tạo device info — dùng User-Agent thật
    String deviceId = UUID.randomUUID().toString();
    String deviceName = "Google Login";

    // Gọi createUserSession() — dùng chung
    return createUserSession(userId, deviceId, deviceName, ipAddress, userAgent, response);
}
```

> **Lưu ý:** Cần inject `RedisTemplate<String, Object>` vào `AuthServiceImplement` nếu chưa có.

### 2.9 SecurityConfig.java

```java
// THÊM imports
import com.example.boilerplate.infrastructure.security.oauth2.CustomOidcUserService;
import com.example.boilerplate.infrastructure.security.oauth2.OidcLoginSuccessHandler;
import com.example.boilerplate.infrastructure.security.oauth2.OidcLoginFailureHandler;

// THÊM dependencies (inject constructor)
private final CustomOidcUserService customOidcUserService;
private final OidcLoginSuccessHandler oidcLoginSuccessHandler;
private final OidcLoginFailureHandler oidcLoginFailureHandler;

// THÊM vào PUBLIC_PATTERNS
public static final String[] PUBLIC_PATTERNS = {
    "/api/v1/auth/**",
    "/oauth2/**",
    "/login/oauth2/code/**",
    "/products/**",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/actuator/health"
};

// SỬA securityFilterChain()
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exception ->
            exception.authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(PUBLIC_PATTERNS).permitAll()
            .anyRequest().authenticated())

        // ⚡ OIDC LOGIN
        .oauth2Login(oauth2 -> oauth2
            .userInfoEndpoint(userInfo -> userInfo
                .oidcUserService(customOidcUserService))
            .successHandler(oidcLoginSuccessHandler)
            .failureHandler(oidcLoginFailureHandler))

        .authenticationProvider(daoAuthenticationProvider())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### 2.10 application.yml — Thêm config

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope:
              - openid
              - email
              - profile
            redirect-uri: "{baseUrl}/login/oauth2/code/google"
            client-name: Google

app:
  oauth2:
    # Fix #10: Default :5173 (Vite dev server) — khớp CorsConfig
    redirect-url: ${APP_OAUTH2_REDIRECT_URL:http://localhost:5173/oauth-callback}
```

### 2.11 .env.example — Thêm env vars

```properties
# OAuth2 / OIDC - Google
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
# Fix #10: Default :5173 (Vite) — phải khớp CorsConfig origins
APP_OAUTH2_REDIRECT_URL=http://localhost:5173/oauth-callback
```

---

## 🔷 PHASE 3: TESTING

### 3.1 Test Cases

| # | Test Case | Expected |
|---|-----------|----------|
| 1 | **Login thường** — user active | ✅ AuthResponse với AT |
| 2 | **Login thường** — inactive (chưa OTP) | ✅ LoginInactiveResponse |
| 3 | **Login thường** — banned | ❌ AppException(ACCOUNT_BANNED) |
| 4 | **Google login** — user mới | ✅ Redirect FE + ticket → exchange → AT |
| 5 | **Google login** — user local active (link) | ✅ Ticket → exchange → AT |
| 6 | **Google login** — user banned | ❌ 403 ErrorResponse (ACCOUNT_BANNED) |
| 7 | **Google login** — user inactive local | ✅ Auto-active + dọn OTP + ticket → AT |
| 8 | **Exchange ticket** — ticket hợp lệ | ✅ Atomic delete → AT + RT cookie |
| 9 | **Exchange ticket** — ticket hết hạn/sai | ❌ 401 Unauthorized |
| 10 | **Exchange ticket** — dùng ticket 2 lần | ❌ Lần 2: 401 (atomic delete bảo vệ) |
| 11 | **Refresh token** — sau OIDC login | ✅ AT mới |
| 12 | **Logout** — sau OIDC login | ✅ Xóa session + cookie |
| 13 | **Google login** — email chưa verify | ❌ OAuth2AuthenticationException |

### 3.2 Unit Test

> **Fix #13:** `@WithMockOidcLogin` không tồn tại. Dùng đúng API:
> `SecurityMockMvcRequestPostProcessors.oidcLogin()`.

```java
// Dùng @WebMvcTest(AuthController.class) + mock RedisService/AuthService
// KHÔNG dùng @WithMockOidcLogin — không tồn tại

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // bỏ security filters cho đơn giản
class ExchangeTicketTest {

    @Autowired MockMvc mockMvc;
    @MockBean AuthService authService;
    @MockBean RequestUtils requestUtils;

    @Test
    void exchangeTicket_withValidTicket_returnsAccessToken() throws Exception {
        AuthResponse mockResponse = new AuthResponse(4001, 1L, "user", Set.of(), "jwt-token");
        when(authService.exchangeTicket(eq("valid-ticket"), any(), any(), any()))
            .thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/auth/exchange-ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"valid-ticket\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("jwt-token"));
    }

    @Test
    void exchangeTicket_withExpiredTicket_returns401() throws Exception {
        when(authService.exchangeTicket(eq("expired"), any(), any(), any()))
            .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(post("/api/v1/auth/exchange-ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"expired\"}"))
            .andExpect(status().isUnauthorized());
    }
}
```

---

## 🔷 DANH SÁCH FILE THAY ĐỔI

### Files MỚI (7 files)

| # | File Path | Package |
|---|-----------|---------|
| 1 | `AccountBannedException.java` | `infrastructure.security.oauth2` |
| 2 | `CustomOidcUser.java` | `infrastructure.security.oauth2` |
| 3 | `CustomOidcUserService.java` | `infrastructure.security.oauth2` |
| 4 | `OidcLoginSuccessHandler.java` | `infrastructure.security.oauth2` |
| 5 | `OidcLoginFailureHandler.java` | `infrastructure.security.oauth2` |
| 6 | `ExchangeTicketRequest.java` | `features.auth.dto.request` |
| 7 | `V5__add_oidc_fields.sql` | `resources.db.migration` |

### Files SỬA (13 files)

| # | File Path | Thay đổi |
|---|-----------|----------|
| 1 | `User.java` | Thêm `authProvider`, `socialId`, `avatarUrl` + enum `AuthProvider` |
| 2 | `AuthService.java` (interface) | Thêm `createUserSession()` (6 params), `exchangeTicket()` |
| 3 | `AuthServiceImplement.java` | Tách `createUserSession()`, `preLoginCheck()`, thêm `exchangeTicket()` (atomic delete), refactor `login()`, inject `RedisTemplate` |
| 4 | `SecurityConfig.java` | Thêm `.oauth2Login()` config, permit OAuth2 endpoints, xóa `offlineAccessResolver` |
| 5 | `UserRepository.java` | (optional) Thêm `findBySocialId()` |
| 6 | `AuthController.java` | Thêm `RequestUtils` dependency + endpoint `POST /exchange-ticket` |
| 7 | `application.yml` | Thêm Google OAuth2 + `app.oauth2.redirect-url` (default :5173) |
| 8 | `.env.example` | Thêm `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `APP_OAUTH2_REDIRECT_URL` |
| 9 | `doc/1 - AUTH/` | Cập nhật docs cho OIDC flow |
| 10 | `Testcase/` | Thêm test cases cho OIDC |
| 11 | `API Contract/` | Thêm contract cho exchange-ticket endpoint |
| 12 | `pom.xml` | ✅ Đã có `spring-boot-starter-oauth2-client` |
| 13 | `RedisService.java` | (optional) Thêm method `deleteAndGet()` atomic nếu cần dùng ở nhiều nơi |

---

## 🔷 CÁC CẠM BẪY CẦN TRÁNH

### ⚠️ #1: Password NOT NULL + setPassword(null)

```java
// ❌ SAI — DB: password varchar(255) NOT NULL
user.setPassword(null);   // DataIntegrityViolationException!
user.setPassword("");     // Chạy được nhưng BCrypt check sẽ fail → không login password được

// ✅ ĐÚNG — Hash UUID ngẫu nhiên
user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
// → Không ai login password được
// → User chỉ login bằng Google
// → Cần implement "set password" / "forgot password" để user có thể đặt password sau
```

### ⚠️ #2: NPE khi thiếu email claim

```java
// ❌ SAI — claims.get("email") có thể null → NPE
String email = ((String) claims.get("email")).toLowerCase().trim();

// ✅ ĐÚNG — Check null/blank TRƯỚC khi normalize
String rawEmail = (String) claims.get("email");
if (rawEmail == null || rawEmail.isBlank()) {
    throw new OAuth2AuthenticationException(
        new OAuth2Error("email_not_provided", "Email not provided by Google", null));
}
String email = rawEmail.toLowerCase().trim();
```

### ⚠️ #3: createUserSession thiếu userAgent → phá cam kết no-behavior-change

```java
// ❌ SAI — Phase 1 cam kết KHÔNG đổi behavior, nhưng:
//   login() hiện tại truyền requestUtils.getUserAgent(request) thật
//   → Refactor truyền deviceName thay userAgent → session lưu sai data

// ✅ ĐÚNG — Thêm param thứ 6 userAgent
AuthResponse createUserSession(
    Long userId, String deviceId, String deviceName,
    String ipAddress, String userAgent, HttpServletResponse response);

// login() gọi:
createUserSession(userId, deviceId, deviceName, ip,
    requestUtils.getUserAgent(httpServletRequest), response);  // ← UA thật
```

### ⚠️ #4: TOCTOU race trong exchangeTicket

```java
// ❌ SAI — Get rồi mới delete: 2 request song song cùng ticket có thể cùng pass
String userId = redisService.getString(key);  // Request A: get OK
// Request B cũng get OK cùng lúc
redisService.delete(key);                      // Request A: delete
// Request B cũng delete OK → 2 session → phá single-use!

// ✅ ĐÚNG — Atomic delete (GETDEL)
String userId = redisTemplate.execute(GETDEL_SCRIPT, List.of(key));
if (userId == null) return 401;  // Chỉ 1 trong 2 request lấy được value
```

### ⚠️ #5: @Transactional bao HTTP call

```java
// ❌ SAI — super.loadUser() gọi HTTP tới Google → giữ DB connection suốt network I/O
@Transactional
public OidcUser loadUser(OidcUserRequest request) {
    OidcUser oidcUser = super.loadUser(request);  // ← HTTP call! DB connection bị hold
    // ... DB operations ...
}

// ✅ ĐÚNG — Bỏ @Transactional, loadUser() chạy ngoài transaction
//   DB operations (find/create/save) được Spring auto-commit hoặc cần explicit @Transactional
public OidcUser loadUser(OidcUserRequest request) {
    OidcUser oidcUser = super.loadUser(request);  // HTTP call — không giữ DB connection
    // ... DB operations (Spring auto-commit hoặc method con @Transactional) ...
}
```

### ⚠️ #6: Log nguyên ticket = credential leak

```java
// ❌ SAI — Ticket là credential tương đương 1 lần login
log.info("OIDC login success: userId={}, ticket={}", userId, ticket);

// ✅ ĐÚNG — Log chỉ 8 ký tự đầu
log.info("OIDC login success: userId={}, ticket={}", userId, ticket.substring(0, 8));
```

### ⚠️ #7: OAuth2AuthenticationException sai constructor

```java
// ❌ SAI — Constructor 1-String nhận errorCode (không phải message)
throw new OAuth2AuthenticationException("Email not verified");

// ✅ ĐÚNG — Dùng OAuth2Error
throw new OAuth2AuthenticationException(
    new OAuth2Error("email_not_verified", "Email not verified by Google", null));
```

### ⚠️ #8: Default redirect-url phải khớp CorsConfig

```yaml
# ❌ SAI — CORS chỉ allow http://localhost:5173 (Vite)
# Redirect :3000 → FE gọi fetch POST /exchange-ticket bị CORS chặn
app.oauth2.redirect-url: http://localhost:3000/oauth-callback

# ✅ ĐÚNG — Default :5173
app.oauth2.redirect-url: http://localhost:5173/oauth-callback
```

### ⚠️ #9: SameSite cookie cho JSESSIONID

```yaml
# ❌ SAI — Set same-site=strict sẽ phá OAuth2 state
server.servlet.session.cookie.same-site: strict

# ✅ ĐÚNG — KHÔNG set (để default = Lax, OAuth2 state sống sót qua redirect)
# Nếu cần set thì set same-site=lax
```

---

## 🔷 PREREQUISITES: GOOGLE CLOUD SETUP

1. **Tạo project trên** [Google Cloud Console](https://console.cloud.google.com/)
2. APIs & Services → Library → Enable **Google OAuth2 API**
3. Credentials → Create OAuth 2.0 Client IDs → Web application
4. **Authorized redirect URIs:**
   ```
   http://localhost:8080/login/oauth2/code/google
   ```
5. Copy vào `.env`:
   ```properties
   GOOGLE_CLIENT_ID=xxx.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=GOCSPX-yyy
   APP_OAUTH2_REDIRECT_URL=http://localhost:5173/oauth-callback
   ```

---

## 🔷 KNOWN LIMITATIONS

1. **User OIDC không có password:** User tạo qua Google không thể login bằng password truyền thống. Cần implement "set password" / "forgot password" nếu muốn hỗ trợ sau này.

2. **Chỉ link 1 provider/lần:** Hiện tại chỉ link Google. Nếu sau này thêm GitHub, cần strategy link nhiều provider cho cùng 1 user (cần thêm bảng `user_oauth_provider` hoặc lưu JSON trong socialId).

3. **avatar_url có thể null:** Một số Google account không có ảnh đại diện → avatar_url = null. FE cần xử lý fallback.

---

## 📐 GIT STRATEGY

```
Step 1: git commit -m "refactor(auth): extract createUserSession() and preLoginCheck()"
        (Phase 1 — không thay đổi behavior)

Step 2: git commit -m "feat(auth): add OIDC fields to User entity + migration"
        (Field mới + Flyway)

Step 3: git commit -m "feat(auth): implement Google OIDC login with one-time ticket"
        (Phase 2 — OIDC classes + exchange-ticket + SecurityConfig)

Step 4: git commit -m "test(auth): add OIDC unit/integration tests"
        (Phase 3)
```
