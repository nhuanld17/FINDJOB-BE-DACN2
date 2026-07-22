# OIDC Login Flow — Đăng nhập bằng Google

> **Mục đích:** Tài liệu này mô tả chi tiết luồng hoạt động từ lúc người dùng click "Login with Google" cho đến khi nhận được access token, giải thích từng thành phần tham gia và dữ liệu được truyền đi.

---

## 🌐 Tổng quan kiến trúc

```
┌─────────────────┐       ┌──────────────────┐       ┌──────────────┐
│   Browser (FE)  │       │   Backend (BE)   │       │   Google     │
│  localhost:5173 │       │ localhost:8080   │       │   accounts   │
└────────┬────────┘       └────────┬─────────┘       └──────┬───────┘
         │                         │                        │
         │  1. /oauth2/authorization/google                 │
         │────────────────────────►│                        │
         │                         │  2. Redirect Google    │
         │                         │───────────────────────►│
         │                         │                        │
         │                         │   3. Login + consent   │
         │                         │◄───────────────────────│
         │                         │                        │
         │                         │  4. Auth code callback │
         │                         │◄───────────────────────│
         │                         │                        │
         │                         │  5. Exchange code → token
         │                         │───────────────────────►│
         │                         │  6. ID Token + UserInfo│
         │                         │◄───────────────────────│
         │                         │                        │
         │                         │  7. Xử lý user (tạo/link)
         │                         │                        │
         │                         │  8. Tạo one-time ticket│
         │  9. Redirect /oauth-callback?ticket=xxx          │
         │◄────────────────────────│                        │
         │                         │                        │
         │ 10. POST /exchange-ticket                        │
         │────────────────────────►│                        │
         │                         │ 11. Tạo session + token│
         │ 12. AuthResponse (AT)   │                        │
         │◄────────────────────────│                        │
```

---

## 🧩 Các thành phần tham gia

### 1. `SecurityConfig.java`
- **Vai trò:** Cấu hình bảo mật Spring Security
- **Nhiệm vụ:**
  - Public endpoint `/oauth2/**` và `/login/oauth2/code/**` — không yêu cầu JWT
  - Đăng ký `CustomOidcUserService` vào OAuth2 login pipeline
  - Gắn `OidcLoginSuccessHandler` và `OidcLoginFailureHandler`
  - Chặn tất cả request khác bằng `JwtAuthFilter`

### 2. `CustomOidcUserService.java`
- **Vai trò:** Xử lý thông tin user từ Google
- **Nhiệm vụ:**
  - Gọi Google API để lấy ID Token + UserInfo
  - Extract claims (email, name, sub, picture)
  - Tìm/create user trong DB
  - Trả về `CustomOidcUser` chứa userId local

### 3. `CustomOidcUser.java`
- **Vai trò:** OidcUser mở rộng
- **Nhiệm vụ:**
  - Kế thừa `DefaultOidcUser`
  - Thêm field `userId` (ID local trong DB) + `email`
  - Dùng để truyền userId từ `CustomOidcUserService` → `OidcLoginSuccessHandler`

### 4. `OidcLoginSuccessHandler.java`
- **Vai trò:** Xử lý sau khi Google xác thực thành công
- **Nhiệm vụ:**
  - Lấy userId từ `CustomOidcUser`
  - Tạo one-time ticket (UUID)
  - Lưu ticket vào Redis (TTL 60s)
  - Redirect browser về FE với ticket trên URL

### 5. `OidcLoginFailureHandler.java`
- **Vai trò:** Xử lý khi Google xác thực thất bại
- **Nhiệm vụ:**
  - Phân loại lỗi (AccountBannedException → 403, còn lại → 401)
  - Trả về `ErrorResponse` JSON

### 6. `AuthController.java` — endpoint `/exchange-ticket`
- **Vai trò:** Nhận ticket từ FE, gọi service để đổi lấy access token
- **Nhiệm vụ:**
  - Validate request body (`@Valid ExchangeTicketRequest`)
  - Lấy client IP + User-Agent từ request
  - Gọi `authService.exchangeTicket(...)`

### 7. `AuthServiceImplement.java` — `exchangeTicket()` + `createUserSession()`
- **Vai trò:** Xương sống của flow — xử lý business logic
- **Nhiệm vụ:**
  - `exchangeTicket()`: Kiểm tra ticket trong Redis (atomic GETDEL), parse userId
  - `createUserSession()`: Tạo session Redis, JWT access/refresh token, set cookie

### 8. Redis
- **Vai trò:** Lưu trữ tạm thời (cache)
- **Dữ liệu:**
  - `oauth2:ticket:<uuid>` → `userId` (TTL 60s)
  - `session:<sessionId>` → Hash chứ thông tin session
  - `user:sessions:<userId>` → Set chứa danh sách sessionId

---

## 🔄 Luồng chi tiết từng bước

---

### Bước 1: User click "Login with Google"

**URL:** `http://localhost:8080/oauth2/authorization/google`

Spring Security nhận request, tạo `OAuth2AuthorizationRequest` với:
- `client_id` = `GOOGLE_CLIENT_ID` (từ `application.yml`)
- `redirect_uri` = `http://localhost:8080/login/oauth2/code/google`
- `scope` = `openid email profile`
- `response_type` = `code`

> `redirect_uri` phải **khớp chính xác** với URI đã khai báo trong Google Cloud Console.

---

### Bước 2: Browser redirect đến Google login page

Spring Security trả về HTTP 302, browser chuyển hướng đến:
```
https://accounts.google.com/o/oauth2/v2/auth?
  client_id=xxx&
  redirect_uri=http://localhost:8080/login/oauth2/code/google&
  scope=openid+email+profile&
  response_type=code&
  ...
```

---

### Bước 3: User đăng nhập + consent trên Google

User nhập email/mật khẩu, Google xác thực. (Nếu là lần đầu, Google hỏi consent — đồng ý chia sẻ email, profile, etc.)

---

### Bước 4: Google redirect về callback endpoint

Sau khi đăng nhập thành công, Google gửi HTTP redirect đến:
```
http://localhost:8080/login/oauth2/code/google?code=AUTH_CODE&state=STATE
```

Spring Security nhận request này thông qua `OAuth2LoginAuthenticationFilter`.

---

### Bước 5: Backend exchange authorization code → ID Token

Spring Security tự động gọi Google:
```
POST https://oauth2.googleapis.com/token
  client_id=xxx
  client_secret=xxx
  code=AUTH_CODE
  redirect_uri=http://localhost:8080/login/oauth2/code/google
  grant_type=authorization_code
```

Google trả về:
```json
{
  "access_token": "...",
  "id_token": "eyJhbGciOiJSUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

---

### Bước 6: Backend lấy UserInfo từ ID Token

Spring Security giải mã `id_token` (JWT) lấy claims. Sau đó gọi tiếp `UserInfo endpoint` nếu cần:
```
GET https://www.googleapis.com/oauth2/v3/userinfo
Authorization: Bearer access_token
```

> `CustomOidcUserService.loadUser()` được Spring Security gọi ở bước này.

---

### Bước 7: `CustomOidcUserService.loadUser()` — Xử lý user

Đây là bước quan trọng nhất. `CustomOidcUserService` (kế thừa `OidcUserService`) override method `loadUser()`:

#### 7a. Gọi `super.loadUser()` — HTTP call đến Google

```
OidcUser oidcUser = super.loadUser(userRequest);
```
- Gửi request HTTP đến Google để lấy ID Token + UserInfo
- **KHÔNG có `@Transactional`** — không giữ DB connection khi chờ Google

#### 7b. Extract & validate claims

```java
String rawEmail = (String) claims.get("email");
String name = (String) claims.get("name");
String sub = (String) claims.get("sub");           // Google Subject ID
String picture = (String) claims.get("picture");
Boolean emailVerified = (Boolean) claims.get("email_verified");
```

**Validations (security-critical):**
1. Email **null/blank** → throw `OAuth2AuthenticationException("email_not_provided")`
2. Email **chưa verify** (`email_verified != true`) → throw `OAuth2AuthenticationException("email_not_verified")`
   - Ngăn chặn **account-takeover**: attacker không thể tạo Google account email chưa verify để chiếm victim account

#### 7c. Chuẩn hóa email

```java
String email = rawEmail.toLowerCase().trim();
```
→ Khớp với format email khi register local (lowercase + trim).

#### 7d. Tìm user trong DB

```java
User user = userRepository.findByEmailWithRoles(email).orElse(null);
```

Dùng `findByEmailWithRoles` — **JOIN FETCH roles** — để tránh `LazyInitializationException` khi truy cập roles sau này (vì `loadUser()` không có `@Transactional`).

---

#### 🟢 Nhánh A: User mới (user == null)

Tạo tài khoản mới từ thông tin Google:

```java
user.setUsername(email);                              // Username = email
user.setPassword(passwordEncoder.encode(UUID...));    // Password random (không ai login được)
user.setActive(true);                                 // Google đã verify → auto active
user.setAuthProvider(AuthProvider.GOOGLE);            // Đánh dấu tài khoản Google
user.setSocialId(sub);                                // sub từ Google
user.getRoles().add(roleRepository.findByName(USER)); // Gán role USER
```

> **Tại sao set password random?** DB có `NOT NULL` constraint. Hash UUID ngẫu nhiên an toàn hơn placeholder string vì `BCryptPasswordEncoder.matches()` sẽ trả về `false` cho mọi password, không throw exception.

---

#### 🔵 Nhánh B: User đã tồn tại (user != null)

Kiểm tra lần lượt:

| Step | Check | Hành động |
|------|-------|-----------|
| 5 | `user.isDeleted()` | ❌ Throw `AccountBannedException` → 403 |
| 6 | `!user.isActive()` | ✅ **Auto-activate** + clear OTP Redis state |
| 7a | `socialId == null` | ✅ **Link Google** — set `socialId = sub` |
| 7b | `avatarUrl == null` | ✅ Set `avatarUrl = picture` từ Google |

> **Chỉ save khi có mutation** — tránh `updated_at` bị thay đổi mỗi lần login.
>
> **KHÔNG ghi đè `authProvider`** — nếu user đăng ký local trước (authProvider = LOCAL), giữ nguyên. Chỉ thêm `socialId` để biết đã link Google.

#### 7e. Tạo `CustomOidcUser`

```java
List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
    .map(role -> new SimpleGrantedAuthority(role.getName().getAuthority()))
    .toList();

return new CustomOidcUser(
    authorities,
    oidcUser.getIdToken(),
    oidcUser.getUserInfo(),
    user.getId(),        // ← userId local, quan trọng cho bước tiếp
    user.getEmail()
);
```

---

### Bước 8: `OidcLoginSuccessHandler.onAuthenticationSuccess()` — Tạo ticket

Spring Security gọi `OidcLoginSuccessHandler` sau khi `CustomOidcUserService` trả về thành công.

```java
// 1. Lấy userId từ CustomOidcUser
CustomOidcUser oidcUser = (CustomOidcUser) authentication.getPrincipal();
Long userId = oidcUser.getUserId();

// 2. Tạo one-time ticket (UUID)
String ticket = UUID.randomUUID().toString();

// 3. Lưu vào Redis: oauth2:ticket:<uuid> → userId (TTL 60s)
redisService.set("oauth2:ticket:" + ticket, userId.toString(), 60, TimeUnit.SECONDS);

// 4. Redirect về FE
response.sendRedirect("http://localhost:5173/oauth-callback?ticket=" + ticket);
```

> **Security design:**
> - Ticket là **one-time**: sau khi lấy userId từ Redis sẽ xóa ngay
> - Ticket có **TTL 60s**: giới hạn thời gian dùng
> - Log chỉ ghi **8 ký tự đầu** của ticket — không leak credential

---

### Bước 9: Browser redirect về FE với ticket

Trình duyệt nhận HTTP 302 và chuyển hướng đến:
```
http://localhost:5173/oauth-callback?ticket=abc-123-def-456-...
```

> Lúc này FE (React/React Native) đọc `ticket` từ URL query params.

---

### Bước 10: FE gọi `/api/v1/auth/exchange-ticket`

```
POST /api/v1/auth/exchange-ticket
Content-Type: application/json

{
  "ticket": "abc-123-def-456-..."
}
```

---

### Bước 11: Backend xử lý exchange-ticket

#### Controller nhận request:
```java
AuthResponse response = authService.exchangeTicket(
    request.ticket(),
    requestUtils.getClientIp(httpServletRequest),   // client IP
    requestUtils.getUserAgent(httpServletRequest),  // User-Agent thật
    httpServletResponse
);
```

#### `AuthServiceImplement.exchangeTicket()`:

```java
String redisKey = "oauth2:ticket:" + ticket;

// Atomic GETDEL — lấy và xóa trong 1 bước (Redis Lua script)
String userIdStr = redisTemplate.execute(
    "local v = redis.call('GET', KEYS[1]); " +
    "if v then redis.call('DEL', KEYS[1]) end; " +
    "return v;",
    List.of(redisKey)
);

if (userIdStr == null || userIdStr.isBlank()) {
    throw new AppException(ErrorCode.UNAUTHORIZED);  // Ticket sai/hết hạn
}

Long userId = Long.parseLong(userIdStr);

// Tạo session
return createUserSession(
    userId,
    deviceId = UUID.randomUUID().toString(),     // Tự sinh deviceId
    deviceName = "Google Login",                  // Phân biệt session từ Google
    clientIp,                                     // IP thật của client
    userAgent,                                    // User-Agent thật
    httpServletResponse
);
```

> **Tại sao dùng Lua script thay vì GET + DEL?**
> - `GET + DEL` (2 lệnh riêng) có **race condition**: 2 request song song cùng 1 ticket → cả 2 đều thấy ticket còn tồn tại → dùng được 2 lần
> - **GETDEL** (hoặc Lua script) là atomic: chỉ 1 request lấy được value, request kia nhận `null`

#### `createUserSession()` — dùng chung cho cả login thường và OIDC:

```java
// 1. preLoginCheck(userId) — check user tồn tại + không bị banned
User user = userRepository.findByIdWithRoles(userId)
    .orElseThrow(() -> AppException(USER_NOT_FOUND));
if (user.isDeleted()) throw AppException(ACCOUNT_BANNED);

// 2. Tạo sessionId (UUID)
String sessionId = UUID.randomUUID().toString();

// 3. Build CustomUserDetails + tạo JWT
CustomUserDetails userDetails = new CustomUserDetails(user);
String accessToken = jwtUtil.generateAccessToken(userDetails, sessionId, deviceId);
String refreshToken = jwtUtil.generateRefreshToken(userDetails, sessionId, deviceId);

// 4. Lưu session vào Redis
redisService.createSession(sessionId, username, deviceId, refreshJti,
                           deviceName, ipAddress, userAgent);

// 5. Lưu session vào danh sách session của user
redisService.addSessionToUser(userId.toString(), sessionId);

// 6. Set refresh token cookie (HttpOnly, SameSite=Strict, 7 ngày)
writeCookie(response, "refreshToken", refreshToken, httpOnly=true, 
            secure=true, path="/", sameSite="Strict", maxAge=7*24*3600);

// 7. Trả về AuthResponse (chứa access token)
return new AuthResponse(LOGIN_SUCCESS, userId, username, roles, accessToken);
```

---

### Bước 12: Response trả về FE

```json
{
  "status": "success",
  "data": {
    "code": 4001,
    "userId": 1,
    "username": "user@gmail.com",
    "roles": [
      { "id": 1, "name": "USER" }
    ],
    "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```

Cookie `refreshToken` được set tự động qua HTTP header `Set-Cookie` (HttpOnly, SameSite=Strict).

---

## 🔐 Chi tiết bảo mật

### 1. Email verification check
`CustomOidcUserService` check `email_verified` từ Google. Nếu email chưa verify → từ chối.
> Ngăn chặn attacker tạo Gmail không verify → chiếm account local.

### 2. One-time ticket + Atomic GETDEL
Ticket chỉ dùng được **1 lần duy nhất**. Dùng Lua script atomic → không race condition.
> Ngăn chặn replay attack: nếu ticket bị lộ, attacker chỉ dùng được 1 lần, và nạn nhân dùng sau sẽ bị từ chối.

### 3. Ticket TTL = 60s
Ticket tự hủy sau 60 giây. Giới hạn thời gian window cho attacker.
> Ngăn chặn slow attack: attacker có 60s để dùng ticket, nếu không → ticket chết.

### 4. Log không leak ticket
Chỉ log 8 ký tự đầu của ticket.
> Ngăn chặn log injection / credential leak qua log file.

### 5. Random password cho OIDC user
`passwordEncoder.encode(UUID.randomUUID().toString())` — không ai login bằng password được.
> Ngăn chặn: user OIDC không thể dùng password để login (vì không biết password). Chỉ login được qua Google.

### 6. HttpOnly + SameSite=Strict cookie
Refresh token được set trong cookie HttpOnly (không JS đọc được) + SameSite=Strict (không gửi trong cross-site request).
> Ngăn chặn XSS + CSRF attack.

### 7. KHÔNG ghi đè authProvider
Nếu user đã register local (authProvider=LOCAL), khi login Google lần đầu, giữ nguyên authProvider, chỉ link socialId.
> Cho phép user có cả 2 cách login (local + Google) trên cùng 1 tài khoản.

---

## ⚙️ Các edge cases đã xử lý

### Case 1: User mới hoàn toàn
**Luồng:** Google login → `findByEmail` → null → tạo user mới với `AuthProvider.GOOGLE`
**Kết quả:** ✅ Tạo tài khoản active, login thành công

### Case 2: User local active, chưa từng link Google
**Luồng:** Google login → `findByEmail` → tìm thấy → `socialId == null` → set socialId + avatar
**Kết quả:** ✅ Link Google vào tài khoản local, login thành công

### Case 3: User local inactive (chưa verify OTP)
**Luồng:** Google login → `findByEmail` → tìm thấy → `isActive = false` → **auto-activate** + clear OTP state
**Kết quả:** ✅ Kích hoạt tài khoản, dọn OTP cũ, login thành công

### Case 4: User đã từng login Google
**Luồng:** Google login → `findByEmail` → tìm thấy → `socialId != null` → skip link → login
**Kết quả:** ✅ Login như bình thường (không mutation)

### Case 5: User bị banned (deleted = true)
**Luồng:** Google login → `findByEmail` → tìm thấy → `isDeleted = true` → throw `AccountBannedException`
**Kết quả:** ❌ `OidcLoginFailureHandler` trả về 403 + ErrorResponse

### Case 6: Exchange ticket 2 lần
**Luồng:** POST exchange-ticket lần 1 → GETDEL thành công → lần 2 → GETDEL null → 401
**Kết quả:** ❌ Lần 1 success, lần 2 401 Unauthorized

### Case 7: Exchange ticket với ticket hết hạn
**Luồng:** Ticket TTL = 60s → đợi >60s → GETDEL null → 401
**Kết quả:** ❌ 401 Unauthorized

### Case 8: Google trả về email chưa verified
**Luồng:** Google login → `email_verified = false` → throw `OAuth2AuthenticationException`
**Kết quả:** ❌ 401, message "Email not verified by Google"

---

## 📊 So sánh: Login thường vs OIDC Login

| Tiêu chí | Login thường | OIDC Login |
|----------|-------------|------------|
| Xác thực | Username + Password → `AuthenticationManager` | ID Token từ Google → `CustomOidcUserService` |
| User inactive | Trả về `LoginInactiveResponse` → verify OTP | Auto-activate |
| `deviceId` | Từ client gửi lên | Tự sinh UUID |
| `deviceName` | Từ client gửi lên | `"Google Login"` |
| AuthProvider | `LOCAL` | `GOOGLE` |
| Tạo session | `createUserSession()` | `exchangeTicket()` → `createUserSession()` |
| Cookie RT | Set trực tiếp | Set qua exchange-ticket |

---

## 📝 Sequence Diagram (dạng text)

```
Browser            Backend              Google             Redis
  │                   │                    │                 │
  │─ GET /oauth2/authorization/google ────►│                 │
  │                   │─ 302 Redirect ────►│                 │
  │◄─ 302 ────────────│                    │                 │
  │                   │                    │                 │
  │─ Login + consent ────────────────────►│                 │
  │                   │◄─ Auth code ───────│                 │
  │                   │                    │                 │
  │                   │─ POST /token ─────►│                 │
  │                   │◄─ ID Token ────────│                 │
  │                   │                    │                 │
  │                   │─ loadUser() ───────│                 │
  │                   │  (validate +       │                 │
  │                   │   find/create user) │                 │
  │                   │                    │                 │
  │                   │─ set(key, userId, 60s) ────────────►│
  │                   │                    │                 │
  │◄─ 302 /oauth-callback?ticket=xxx ─────│                 │
  │                   │                    │                 │
  │─ POST /exchange-ticket ──────────────►│                 │
  │                   │─ GETDEL(ticket) ──────────────────►│
  │                   │◄─ userId ───────────────────────────│
  │                   │                    │                 │
  │                   │─ preLoginCheck() ──│                 │
  │                   │─ createSession() ────────────────►│
  │                   │─ addSessionToUser() ────────────►│
  │                   │                    │                 │
  │◄─ AuthResponse ───│                    │                 │
  │   + Set-Cookie RT │                    │                 │
```

---

## 🔧 Cấu hình liên quan

### `application.yml`
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
              - profile
              - email
            redirect-uri: "{baseUrl}/login/oauth2/code/google"
            client-name: Google
```

### `SecurityConfig.java` — public patterns
```java
public static final String[] PUBLIC_PATTERNS = {
    "/api/v1/auth/**",          // Register, Login, Refresh, Logout, Exchange-ticket
    "/oauth2/**",               // OAuth2 authorization endpoint
    "/login/oauth2/code/**",    // OAuth2 callback endpoint
    ...
};
```

### Redis keys
| Key pattern | Value | TTL |
|------------|-------|-----|
| `oauth2:ticket:<uuid>` | `userId` (String) | 60s |
| `session:<sessionId>` | Hash (username, deviceId, refreshJti, deviceName, ip, userAgent) | 7 ngày |
| `user:sessions:<userId>` | Set of sessionId | Vô thời hạn (xóa thủ công khi logout) |

---

## 🧪 Test flow (bằng HTTP file)

Tạo file `oidc-test.http`:

```http
### Bước 1: Mở URL này trong trình duyệt
### http://localhost:8080/oauth2/authorization/google

### Bước 2: Exchange ticket
### Sau khi login Google, copy ticket từ URL redirect rồi paste vào đây
POST http://localhost:8080/api/v1/auth/exchange-ticket
Content-Type: application/json

{
  "ticket": "PASTE_TICKET_VAO_DAY"
}

### Bước 3: Refresh token (nếu cần)
POST http://localhost:8080/api/v1/auth/refresh-token

### Bước 4: Logout
POST http://localhost:8080/api/v1/auth/logout
Authorization: Bearer PASTE_ACCESS_TOKEN_VAO_DAY
```

---

> **Tài liệu tham khảo:** [Spring Security OAuth2 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html) | [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
