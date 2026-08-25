# OIDC Login Flow — Đăng nhập bằng Google

> **Mục đích:** Tài liệu này mô tả chi tiết luồng hoạt động từ lúc người dùng click "Login with Google" cho đến khi nhận được access token, giải thích từng thành phần tham gia và dữ liệu được truyền đi.
>
> **Cập nhật:** Hỗ trợ dual-mode **Web (React SPA)** + **Mobile (React Native)**.

---

## 🌐 Tổng quan kiến trúc

```
┌─────────────────────┐       ┌──────────────────┐       ┌──────────────┐
│  Web / Mobile       │       │   Backend (BE)   │       │   Google     │
│  localhost:5173     │       │ localhost:8080   │       │   accounts   │
│  findjob://         │       └────────┬─────────┘       └──────┬───────┘
└──────────┬──────────┘               │                        │
           │                          │                        │
           │ ① /oauth2/authorization/google                    │
           │    (?return_url=...)      │                        │
           │─────────────────────────►│                        │
           │                          │ ① Lưu state +          │
           │                          │   return_url (Redis)   │
           │                          │   ────► Redis          │
           │                          │                        │
           │                          │ ② Redirect Google      │
           │                          │   (nonce + PKCE)       │
           │                          │───────────────────────►│
           │                          │                        │
           │                          │ ③ Login + consent      │
           │                          │◄───────────────────────│
           │                          │                        │
           │                          │ ④ Auth code callback   │
           │                          │◄───────────────────────│
           │                          │                        │
           │                          │ ④ Load state +         │
           │                          │   validate CSRF (Redis)│
           │                          │  ◄─── Redis            │
           │                          │                        │
           │                          │ ⑤ Exchange code →      │
           │                          │   ID Token + AT        │
           │                          │───────────────────────►│
           │                          │◄───────────────────────│
           │                          │                        │
           │                          │ ⑥ Validate JWT         │
           │                          │   (chữ ký + claims)    │
           │                          │                        │
           │                          │ ⑦ CustomOidcUserService│
           │                          │   (tìm/tạo user DB)    │
           │                          │                        │
           │                          │ ⑧ Tạo ticket +         │
           │                          │   đọc return_url       │
           │                          │   ──── Redis ────►     │
           │                          │                        │
           │ ⑨ Redirect: /oauth-callback?ticket=xxx            │
           │    (web) / findjob://... (mobile)                  │
           │◄─────────────────────────│                        │
           │                          │                        │
           │ ⑩ POST /exchange-ticket  │                        │
           │─────────────────────────►│                        │
           │                          │ ⑪ GETDEL ticket +      │
           │                          │   tạo session + JWT    │
           │                          │  ◄─── Redis ────►      │
           │                          │                        │
           │ ⑫ AuthResponse (AT + RT) │                        │
           │◄─────────────────────────│                        │
```

---

## 🧩 Các thành phần tham gia

### 1. `SecurityConfig.java`
- **Vai trò:** Cấu hình bảo mật Spring Security
- **Nhiệm vụ:**
  - Public endpoint `/oauth2/**` và `/login/oauth2/code/**` — không yêu cầu JWT
  - Đăng ký `CustomOidcUserService` vào OAuth2 login pipeline
  - Gắn `OidcLoginSuccessHandler` và `OidcLoginFailureHandler`
  - Gắn `RedisOAuth2AuthorizationRequestRepository` (thay thế `HttpSessionOAuth2AuthorizationRequestRepository` mặc định)
  - **Bật PKCE (Proof Key for Code Exchange)** qua `DefaultOAuth2AuthorizationRequestResolver` + `OAuth2AuthorizationRequestCustomizer.withPkce()`
  - Chặn tất cả request khác bằng `JwtAuthFilter`
  - **`SessionCreationPolicy.STATELESS`** — không dùng HTTP Session

### 2. `RedisOAuth2AuthorizationRequestRepository.java` 🔥 (MỚI)
- **Vai trò:** Lưu trữ `OAuth2AuthorizationRequest` vào Redis thay vì HTTP Session
- **Nhiệm vụ:**
  - Khi user gọi `/oauth2/authorization/google`:
    - Tạo `stateId` (UUID)
    - Lưu `OAuth2AuthorizationRequest` vào Redis với key `oauth2:state:<stateId>` (TTL 120s)
    - Set cookie `oauth2_state` chứa `stateId` (HttpOnly, SameSite=Lax)
    - **Nếu có param `return_url`** (mobile) → lưu vào Redis key `oauth2:return:<state>` (TTL 120s)
  - Khi Google callback về `/login/oauth2/code/google`:
    - Đọc cookie `oauth2_state` → lấy `stateId`
    - Load `OAuth2AuthorizationRequest` từ Redis
  - **Sau khi xử lý authentication xong** (thành công hoặc thất bại):
    - `removeAuthorizationRequest()` → gọi `removeState()`:
      - Xoá Redis key `oauth2:state:<stateId>` (dọn dẹp)
      - Clear cookie `oauth2_state` (set maxAge = 0)
    - Spring Security gọi tự động qua `OAuth2LoginAuthenticationFilter` (finally block)
  - Dùng **JDK serialization** (`JdkSerializationRedisSerializer`) vì `OAuth2AuthorizationRequest` không compatible với Jackson
  - Inject qua `@Qualifier("oauth2StateRedisTemplate")` — một `RedisTemplate` riêng trong `RedisConfig.java`

> **Tại sao cần class này?** Vì `SessionCreationPolicy.STATELESS` — không có HTTP Session → không thể dùng `HttpSessionOAuth2AuthorizationRequestRepository` mặc định.

### 3. `CustomOidcUserService.java`
- **Vai trò:** Xử lý thông tin user từ Google — **class quan trọng nhất** trong flow
- **Nhiệm vụ:**
  - Gọi `super.loadUser()` → Google UserInfo API, lấy claims (email, sub, picture…)
  - Validate `email_verified` — chặn account-takeover
  - Tìm user trong DB local theo email
  - **Phân nhánh:**
    - **User mới:** Tạo `User` + `Employee` profile, set `AuthProvider.GOOGLE`
    - **User đã tồn tại:** Link Google (`socialId`), auto-activate nếu inactive, xử lý `pendingAccountType` (EMPLOYER → tạo Company)
  - Trả về `CustomOidcUser` chứa `userId` local
  - **KHÔNG `@Transactional`** — `super.loadUser()` gọi HTTP đến Google, không nên giữ DB connection

### 4. `CustomOidcUser.java`
- **Vai trò:** OidcUser mở rộng
- **Nhiệm vụ:**
  - Kế thừa `DefaultOidcUser`
  - Thêm field `userId` (ID local trong DB) + `email`
  - Override `getName()` → trả về `email` thay vì `sub` (nhất quán với JWT subject)
  - Cầu nối giữa thông tin OIDC từ Google và user trong DB local

### 5. `OidcLoginSuccessHandler.java`
- **Vai trò:** Xử lý sau khi Google xác thực thành công
- **Nhiệm vụ:**
  - Lấy `userId` từ `CustomOidcUser`
  - Tạo one-time ticket (UUID), lưu Redis `oauth2:ticket:<uuid>` → userId (TTL 60s)
  - **Mobile:** Đọc state param, GETDEL `oauth2:return:<state>` → lấy `return_url`
  - **Validate whitelist** — chỉ redirect về scheme được cho phép (`findjob://`)
  - **Web:** Redirect về `frontendRedirectUrl` (mặc định `http://localhost:5173/oauth-callback?ticket=xxx`)
  - **Mobile:** Redirect về `return_url?ticket=xxx` (VD: `findjob://oauth/callback?ticket=xxx`)

### 6. `OidcLoginFailureHandler.java`
- **Vai trò:** Xử lý khi Google xác thực thất bại — **dual-mode response**
- **Nhiệm vụ:**
  - Phân loại lỗi:
    - `AccountBannedException` → HTTP **403**, code **2007**
    - Mọi lỗi khác → HTTP **401**, code **3008**
  - **Mobile (có return_url + whitelist):** Redirect về `{returnUrl}?error={errorCode}` — app mobile xử lý
  - **Web (không có return_url):** Trả JSON `ErrorResponse`

### 7. `AuthController.java` — endpoint `/exchange-ticket`
- **Vai trò:** Nhận ticket từ FE, gọi service để đổi lấy access token
- **Nhiệm vụ:**
  - Validate request body (`@Valid ExchangeTicketRequest`)
  - Lấy client IP + User-Agent từ request
  - Gọi `authService.exchangeTicket(...)`

### 8. `AuthServiceImplement.java` — `exchangeTicket()` + `createUserSession()`
- **Vai trò:** Xương sống của flow — xử lý business logic
- **Nhiệm vụ:**
  - `exchangeTicket()`: Kiểm tra ticket trong Redis (atomic GETDEL), parse userId
  - `createUserSession()`: Tạo session Redis, JWT access/refresh token, set cookie
  - Dùng chung cho cả login thường và OIDC

### 9. `RedisConfig.java` — `oauth2StateRedisTemplate`
- **Vai trò:** Config riêng cho RedisTemplate dùng JDK serialization
- **Lý do:** `OAuth2AuthorizationRequest` chứa class `OAuth2AuthorizationResponseType` — Jackson không deserialize được
- `OAuth2AuthorizationRequest` implements `Serializable` → JDK serialization an toàn cho data tạm (TTL 120s)

### 10. Redis
- **Vai trò:** Lưu trữ tạm thời (cache) — 5 loại key

---

## 🔄 Luồng chi tiết từng bước

---

### Bước 1: User click "Login with Google" — FE gửi request đến backend

Tuỳ platform, cách gọi khác nhau:

| Web | Mobile |
|-----|--------|
| `window.location.href = "http://localhost:8080/oauth2/authorization/google"` | `WebBrowser.openAuthSessionAsync(authUrl, redirectUrl)` với `authUrl = apiUrl + "/oauth2/authorization/google?return_url=" + encodeURIComponent(redirectUrl)` |
| Full browser navigation — React App biến mất | In-app browser overlay — app vẫn chạy ngầm |
| **Không** cần gửi `return_url` — backend biết sẵn đích đến | **Cần** gửi `return_url` — backend không biết mobile đang ở deep link nào |

---

**Backend xử lý: `OAuth2AuthorizationRequestRedirectFilter`**

Filter này nhận request, tạo object `OAuth2AuthorizationRequest` chứa:

```java
OAuth2AuthorizationRequest.builder()
    .clientId("GOOGLE_CLIENT_ID")
    .redirectUri("http://localhost:8080/login/oauth2/code/google")  // ← phải khớp Google Cloud Console
    .scopes(Set.of("openid", "email", "profile"))
    .state(UUID.randomUUID().toString())                            // ← state: chống CSRF
    .build();
```

> ⚠️ **Đoạn trên là pseudo-code minh họa** — code thật của dự án KHÔNG có chỗ nào tự dựng `OAuth2AuthorizationRequest` bằng `builder()`. Spring Security tự build object này từ cấu hình. Chi tiết ở mục "Tham số lấy từ đâu?" bên dưới.

---

#### 🔍 Tham số lấy từ đâu? — `application.yml` → `ClientRegistration` → Resolver

Chuỗi thực tế (không có code nào tự gọi `builder()`):

```
application.yml
  └── spring.security.oauth2.client.registration.google.*
        │  Spring Boot auto-configuration → tự tạo bean
        ▼
ClientRegistration  ──►  ClientRegistrationRepository
        │
        ▼  Khi user gọi GET /oauth2/authorization/google
OAuth2AuthorizationRequestRedirectFilter
        │  gọi resolver
        ▼
DefaultOAuth2AuthorizationRequestResolver   ← SecurityConfig.pkceResolver()
        │  đọc ClientRegistration + sinh thêm tham số động
        ▼
OAuth2AuthorizationRequest  →  lưu Redis  →  302 redirect sang Google
```

Bảng mapping giữa `application.yml` và tham số trong request:

| Tham số trong request | Lấy từ đâu |
|---|---|
| `clientId` | `spring.security.oauth2.client.registration.google.client-id` (yml) |
| `clientSecret` | `...google.client-secret` (yml) — chỉ dùng ở Bước 5 (exchange code) |
| `scopes` (`openid`, `email`, `profile`) | `...google.scope` (yml) |
| `redirectUri` | `...google.redirect-uri: "{baseUrl}/login/oauth2/code/google"` (yml) |
| `state` | 🔄 Spring sinh động (UUID) mỗi request — chống CSRF |
| `nonce` | 🔄 Spring sinh động vì có scope `openid` — chống ID Token replay |
| `code_challenge` + `code_challenge_method=S256` | 🔄 Sinh động bởi `OAuth2AuthorizationRequestCustomizers.withPkce()` (SecurityConfig) |
| `authorization-uri`, `token-uri`, `jwk-set-uri` | Mặc định Spring Boot có sẵn cho provider `google` (không cần khai báo trong yml) |

> 🔑 **Mấu chốt:** `ClientRegistration` là "hồ sơ đăng ký app" — Spring Boot đọc `registration.google.*` trong yml và tự tạo bean này. Resolver chỉ việc đọc từ repository + sinh thêm `state`/`nonce`/PKCE rồi gói thành `OAuth2AuthorizationRequest`. Muốn đổi `client-id`, scope hay `redirect-uri` → **chỉ sửa yml, không cần sửa code Java**.

> ℹ️ **`{baseUrl}` trong redirect-uri là placeholder** — Spring Security thay nó bằng `scheme://host:port` của request hiện tại tại runtime. Vì vậy `forward-headers-strategy: framework` là bắt buộc khi chạy sau reverse proxy (nếu không, Spring tính ra `http://...` sai scheme → Google báo `redirect_uri_mismatch`).

---

#### 🆚 Vì sao application.yml có 2 tham số redirect?

Hai tham số này phục vụ **2 lần redirect khác nhau** trong flow — đừng nhầm:

| | `redirect-uri` | `redirect-url` |
|---|---|---|
| Vị trí trong yml | `spring.security.oauth2.client.registration.google.redirect-uri` | `app.oauth2.redirect-url` |
| Giá trị mặc định | `{baseUrl}/login/oauth2/code/google` | `http://localhost:5173/oauth-callback` |
| Lần redirect nào | **Lần 1: Google → Backend** (Bước 4) | **Lần 2: Backend → Web FE** (Bước 8-9) |
| Vai trò | Địa chỉ Google gửi `code` + `state` về sau khi user consent — phải khớp Google Cloud Console | Địa chỉ backend redirect browser web về sau khi login xong, kèm `?ticket=xxx` |
| Là chuẩn OAuth2? | ✅ Đúng — là tham số OAuth2 chính thức | ❌ Không — config riêng của dự án |
| Mobile có dùng? | ✅ Có (cả web & mobile dùng chung) | ❌ Không — mobile dùng `return_url` động (xem mục phân biệt `redirect_uri` vs `return_url` ở cuối doc) |

Sau đó request object này được ghi vào Redis qua `RedisOAuth2AuthorizationRequestRepository`:

```java
// --- Repository lưu 2 thứ vào Redis ---

// THỨ 1: Toàn bộ OAuth2AuthorizationRequest (JDK serialized)
String stateId = UUID.randomUUID().toString();    // stateId = UUID riêng, để làm key
redisTemplate.set(Oauth2Constant.STATE_PREFIX + stateId  // prefix "oauth2:state:" định nghĩa trong common/constant/Oauth2Constant, authorizationRequest, 120s);
// Set cookie "oauth2_state" chứa stateId (HttpOnly, SameSite=Lax)
writeCookie(request, response, stateId, 120);
// Cookie này được trình duyệt web (tab chính) hoặc in-app browser (mobile)
// lưu lại tự động. Đến Bước 4, khi Google redirect callback về,
// browser/in-app browser sẽ gửi cookie này kèm trong request.

// THỨ 2: Nếu là mobile (có return_url) — lưu riêng return_url
String returnUrl = request.getParameter("return_url");
if (returnUrl != null && isAllowedMobileScheme(returnUrl)) {
    stringRedisTemplate.set(
        Oauth2Constant.RETURN_PREFIX + authorizationRequest.getState(),  // key = OAuth state, KHÔNG phải stateId
        returnUrl,
        120s
    );
}
```

> **Phân biệt 2 loại "state"** (dễ nhầm nhất trong OIDC flow):
> - **`stateId`** (UUID do backend tự tạo) — dùng để làm **key** cho Redis `oauth2:state:*`, lưu trong cookie `oauth2_state` để load lại request khi callback
> - **`authorizationRequest.getState()`** (OAuth state param) — gửi lên Google như 1 param trong URL, Google gửi trả lại trong callback, dùng để **validate CSRF** và làm key cho `oauth2:return:*`
> 
> Nôm na: `stateId` là **số thứ tự tủ hồ sơ** (backend tự đánh số cho dễ tìm). `OAuth state` là **mã xác thực** (cảnh sát Google gửi trả để chứng minh không ai giả mạo).

Lưu xong, `OAuth2AuthorizationRequestRedirectFilter` trả về HTTP 302 redirect trình duyệt sang Google login page (Bước 2).

---

### Bước 2: Browser redirect đến Google login page

Spring Security dùng `DefaultOAuth2AuthorizationRequestResolver` (đã cấu hình PKCE) để build URL, trả về HTTP 302:

```
https://accounts.google.com/o/oauth2/v2/auth?
  client_id=xxx&
  redirect_uri=http://localhost:8080/login/oauth2/code/google&
  scope=openid+email+profile&
  response_type=code&
  state=OAUTH_STATE_UUID&
  nonce=HASHED_NONCE_VALUE&
  code_challenge=GENERATED_CODE_CHALLENGE&
  code_challenge_method=S256&
  ...
```

> **nonce (Number Once) — 🔑 TẠO Ở BƯỚC NÀY, VALIDATE Ở BƯỚC 6:**
>
> Spring Security tự động tạo nonce (UUID hash) khi có `scope=openid`. Nonce được:
> - Gắn vào URL param `nonce=...` gửi lên Google
> - Lưu trong `OAuth2AuthorizationRequest.attributes` — serialized cùng request vào Redis (JDK serialization)
> 
> Khi Google trả về ID Token (Bước 5), JWT sẽ chứa claim `nonce` y hệt. Spring Security ở **Bước 6** sẽ validate: nonce trong ID Token có khớp với nonce đã lưu không → nếu không khớp → từ chối (ID Token bị replay).
>
> **Tác dụng:** Chặn **ID Token replay attack** — attacker đánh cắp ID Token của victim không thể dùng lại vì nonce không khớp.
>
> **code_challenge + code_challenge_method=S256:** PKCE parameters. Spring Security tạo `code_verifier` (random 43-128 chars), lưu trong `OAuth2AuthorizationRequest.attributes` → hash SHA256 → `code_challenge`. Khi exchange code, gửi `code_verifier` lên Google để verify — chặn authorization code interception attack.

---

### Bước 3: User đăng nhập + consent trên Google

User nhập email/mật khẩu. (Lần đầu → Google hỏi consent — đồng ý chia sẻ email, profile.)

---

### Bước 4: Google redirect về callback endpoint — verify state + load request từ Redis

Sau khi user login thành công trên Google, Google redirect trình duyệt (hoặc in-app browser của mobile) về backend:

```
http://localhost:8080/login/oauth2/code/google?code=AUTH_CODE&state=OAUTH_STATE_UUID
```

Google biết redirect về đâu? Nhờ `redirect_uri` mà backend đã gửi lên ở **Bước 2**:

```
https://accounts.google.com/o/oauth2/v2/auth?
  ...&
  redirect_uri=http://localhost:8080/login/oauth2/code/google   ← Cái này
  ...
```

Sau khi user login xong, Google đọc tham số `redirect_uri` trong authorization request (Bước 2) → dùng chính URL đó để redirect trình duyệt về backend, kèm theo 2 tham số:
- `code=AUTH_CODE` — authorization code, dùng để exchange lấy token ở bước sau
- `state=OAUTH_STATE_UUID` — **state** mà backend đã gửi lên từ Bước 2, Google echo lại y hệt

> **Tóm lại:** `redirect_uri` ở Bước 2 là "địa chỉ giao hàng" — Google hứa sẽ gửi hàng (code + state) về đúng địa chỉ này sau khi user đồng ý. Đến Bước 4, Google giữ lời.

---

**Spring Security xử lý callback — 2 việc:**

**Việc 1: Load lại `OAuth2AuthorizationRequest` đã lưu từ Bước 1**

Dù là web browser hay mobile in-app browser, cookie `oauth2_state` (đã được set ở Bước 1) đều được trình duyệt tự động gửi kèm trong request callback này.

```java
// Đọc cookie oauth2_state → lấy stateId
String stateId = getStateIdFromCookie(request);

// Load request từ Redis bằng stateId
OAuth2AuthorizationRequest loaded = redisTemplate.opsForValue()
    .get(Oauth2Constant.STATE_PREFIX + stateId  // prefix "oauth2:state:" định nghĩa trong common/constant/Oauth2Constant);
```

Lúc này filter có `OAuth2AuthorizationRequest` — object chứa toàn bộ thông tin của request gốc (clientId, scope, redirect_uri, **state gốc**, ...).

**Việc 2: So sánh state — chặn CSRF**

Filter có 2 giá trị state:
- `request.getParameter("state")` = `ABCXYZ` — state **từ Google gửi về** trong URL callback
- `loaded.getState()` = `ABCXYZ` — state **gốc** đã lưu trong Redis từ Bước 1

Nếu 2 giá trị này **giống hệt nhau** → request hợp lệ → tiếp tục xử lý.  
Nếu **khác nhau** → request giả mạo → từ chối ngay.

> **Tại sao phải so sánh state?**
> Giả sử không có state. Kẻ tấn công tự login Google của hắn, lấy authorization code, rồi gửi link `...?code=ATTACKER_CODE` cho nạn nhân. Nạn nhân click vào, backend nhận code, exchange với Google → Google trả token của attacker → backend link Google account của attacker vào victim account. **State ngăn chuyện này**: attacker không biết state thật của victim request, tự bịa 1 state → bị backend từ chối vì không khớp.

> ⚠️ Nếu restart BE giữa bước 2 và 4, Redis vẫn còn state (TTL 120s) → không bị fail như HTTP Session.

State khớp ✅ → filter gọi Google để exchange code lấy token (Bước 5).

---

### Bước 5: Backend exchange authorization code lấy ID Token từ Google

State đã khớp ✅ → `OAuth2LoginAuthenticationFilter` gửi request đến Google để đổi `authorization_code` lấy token.

---

#### Filter gửi gì lên Google?

```
POST https://oauth2.googleapis.com/token
  client_id=xxx
  client_secret=xxx
  code=AUTH_CODE
  redirect_uri=http://localhost:8080/login/oauth2/code/google
  grant_type=authorization_code
  code_verifier=VERIFIER_STRING
```

> ⚠️ **Request này là server-to-server, không đi qua trình duyệt.**
>
> Backend (Java chạy trên server) gửi thẳng HTTP request đến Google — không qua trình duyệt của user. Có nghĩa là:
> - Attacker không thể đọc được nội dung request (không có JavaScript, không có tool dev để xem)
> - Attacker không thể bắt request này vì nó không đi qua mạng public giữa user và server
> - Attacker chỉ có thể lấy được `client_secret`, `code`, `code_verifier` nếu đã hack vào backend — lúc đó bạn có vấn đề lớn hơn nhiều
>
> Đây là lý do `client_secret` tồn tại: backend là nơi duy nhất có thể gửi request này, nên Google tin tưởng.
>
> (Trái ngược với Bước 2, URL redirect trình duyệt sang Google — request đó đi qua trình duyệt nên chỉ gửi được `client_id` + `redirect_uri` + `code_challenge`, KHÔNG gửi `client_secret` hay `code_verifier` gốc.)

Giải thích từng tham số:

**`client_id` + `client_secret`** — xác danh: Google biết ai đang gọi. `client_secret` chỉ backend mới có → gọi là **confidential client**. Nếu là app mobile thuần (không backend), không có secret → **public client**.

**`code`** — authorization code lấy từ URL callback ở Bước 4. Nếu attacker đánh cắp được code này, hắn cũng không exchange được nếu thiếu các tham số còn lại.

**`redirect_uri`** — gửi lại y hệt URL đã dùng ở Bước 2. Google check: nếu không khớp → từ chối. Tác dụng: nếu attacker có code nhưng không biết `redirect_uri` chính xác (vì nó không leak ra ngoài), Google không exchange.

**`grant_type=authorization_code`** — báo cho Google biết đây là giao dịch "đổi code lấy token", chứ không phải refresh token hay password grant.

**`code_verifier`** — PKCE. Nhớ lại Bước 2, backend đã gửi `code_challenge` (hash của `code_verifier`) lên Google. Giờ gửi `code_verifier` gốc để Google hash lại và so sánh. Nếu khớp → chứng minh backend là chủ nhân thực sự của authorization request đó. Lớp bảo vệ thứ 2: ngay cả khi `client_secret` bị lộ, attacker vẫn không exchange được nếu thiếu `code_verifier` (chỉ backend mới biết, lưu trong attributes của `OAuth2AuthorizationRequest` ở Redis).

---

#### Google trả về gì?

Google nhận request, validate tất cả các tham số trên, rồi trả về:

```json
{
  "access_token": "ya29...",                     // Dùng để gọi Google API — mình không dùng
  "id_token": "eyJhbGciOiJSUzI1NiIs...",        // 🔑 QUAN TRỌNG — JWT chứa thông tin user
  "token_type": "Bearer",
  "expires_in": 3600                             // 1 tiếng
}
```

2 thứ trong response:
- **`access_token`** — token để gọi Google API (Google Drive, Calendar...). Dự án này không gọi Google API gì ngoài OIDC → **không dùng**.
- **`id_token`** — **thứ backend thực sự cần**. Đây là JWT đã được Google ký, chứa claims: `email`, `sub` (Google Subject ID), `email_verified`, `name`, `picture`. Spring Security tự động giải mã JWT này ở Bước 6 để lấy thông tin user.

---

#### Nói nôm na

Bước 5 giống như đem **phiếu hẹn (authorization code)** ra quầy Google để đổi lấy **CMND (ID Token)** — chứng minh được user là ai. Để đổi được, backend phải xuất trình:
- Chứng minh thư của mình (`client_secret`)
- Phiếu hẹn gốc (`code`)
- Địa chỉ đã hẹn (`redirect_uri`)
- Mật mã xác nhận (`code_verifier`)

Thiếu 1 trong 4 thứ → Google từ chối.

→ Sau bước này, backend đã có ID Token. Bước tiếp theo là giải mã nó để lấy thông tin user (Bước 6).

---

### Bước 6: Backend giải mã ID Token — lấy thông tin user từ JWT

Sau khi Google trả về token (Bước 5), backend có `id_token` — một JWT đã được Google ký. Spring Security không exchange luôn mà phải giải mã + xác thực nó trước.

---

#### Việc 1: Spring Security validate chữ ký của JWT

```
ID Token: eyJhbGciOiJSUzI1NiIs... (JWT có 3 phần: header.payload.signature)

1. Header:  { "alg": "RS256", "kid": "abc123" }
2. Payload: { "sub": "1234567890", "email": "john.doe@gmail.com", ... }
3. Signature: [chữ ký số RSA của Google]
```

Spring Security tự động:
1. Tải public key của Google từ `https://www.googleapis.com/oauth2/v3/certs`
2. Dùng `kid` trong header để chọn đúng public key
3. Xác thực chữ ký RSA — nếu sai → từ chối (token giả mạo)

> **Cơ chế chữ ký số RSA hoạt động thế nào?**
>
> JWT dùng thuật toán **RS256** = RSA + SHA-256. Đây là chữ ký số bất đối xứng — dùng 2 key khác nhau cho 2 việc:
>
> **Google (ký):**
> ```
> header + "." + payload  →  SHA256 hash  →  RSA encrypt(HASH, private_key)  →  signature
> ```
> Google giữ private key — không ai biết. Chỉ Google mới ký được.
>
> **Backend (xác thực):**
> ```
> header + "." + payload  →  SHA256 hash = H1
> signature              →  RSA decrypt(signature, public_key) = H2
> H1 == H2?  →  Nếu bằng nhau → chữ ký đúng (vì chỉ private key của Google mới mã hoá được HASH đúng)
> ```
> Backend chỉ cần public key của Google — public key này ai cũng có thể xem, nhưng chỉ dùng để giải mã, không dùng để ký được.
>
> Nôm na: Google đóng dấu (ký) bằng **con dấu riêng** — không ai có. Backend tra dấu bằng **khuôn dấu mẫu** (public) — ai cũng có nhưng chỉ để tra, không đóng giả được.
>
> **Vì sao cần `kid` trong header JWT?**
> Google không chỉ có 1 cặp key — họ xoay vòng nhiều key để bảo mật. `kid` (Key ID) cho backend biết: dùng public key nào để xác thực. Nếu không có `kid`, backend phải thử từng key — mất thời gian.
> Backend tải danh sách public keys từ:
> ```
> GET https://www.googleapis.com/oauth2/v3/certs
> → [
>     { "kid": "abc123", "kty": "RSA", "n": "modulus...", "e": "AQAB" },
>     { "kid": "def456", "kty": "RSA", "n": "modulus...", "e": "AQAB" }
>   ]
> ```
> Mỗi object là 1 JWK (JSON Web Key) — chứa modulus `n` và exponent `e` để tạo thành public key RSA.
> Spring Security cache danh sách này, chỉ tải lại khi gặp `kid` lạ.

---

#### Việc 2: Validate các claims trong JWT

Sau khi chữ ký hợp lệ, Spring Security check tiếp các claim bên trong payload:

| Claim | Check | Tác dụng |
|-------|-------|----------|
| `iss` (issuer) | `https://accounts.google.com` | Chống token từ Google khác (VD: Google Workspace fake) |
| `aud` (audience) | `client_id` của mình | Chống token Google cấp cho app khác dùng vào app mình |
| `exp` (expiration) | Chưa hết hạn | Chống dùng token cũ |
| `nonce` | Khớp với nonce đã **tạo ở Bước 2** (lưu trong Redis cùng `OAuth2AuthorizationRequest.attributes`) | Chống **ID Token replay attack**: nếu attacker đánh cắp ID Token, `nonce` claim không khớp với nonce gốc → từ chối (xem Bước 2 để biết cách tạo) |

> **Nonce chống replay attack thế nào?**
> Giả sử không có nonce. Attacker chặn được ID Token khi nó đi qua mạng (Bước 5 → Backend). Hắn lấy JWT này và gửi lại cho backend sau 1 giờ. Backend check chữ ký vẫn OK (Google ký, không giả được), check `exp` cũng OK nếu chưa hết hạn → attacker login thành công với tư cách victim.
>
> Nonce ngăn chuyện này: mỗi lần OIDC flow bắt đầu, backend tạo 1 nonce mới (Bước 2) — chỉ dùng 1 lần duy nhất. Khi Google trả ID Token, backend kiểm tra nonce trong JWT có khớp với nonce đã lưu không. Nếu attacker dùng lại ID Token cũ, nonce trong JWT không khớp với nonce của request hiện tại → từ chối.
>
> Nôm na: nonce giống như **số thứ tự phiếu** — mỗi lần mua vé là 1 số mới. Attacker nhặt được vé cũ mang ra rạp, so số vé không khớp với số vé đang bán → biết ngay vé giả.

Tất cả các check này đều do Spring Security tự động làm — không cần code tay.

---

#### Việc 3: Chuyển claims thành object + gọi CustomOidcUserService

Sau khi validation OK, Spring Security:
1. Parse các claims thành `OidcUser` object (chứa email, sub, picture...)
2. Gọi `CustomOidcUserService.loadUser()` — truyền vào object chứa ID Token + claims

Đây chính là bước mà code của mình can thiệp vào. Chi tiết ở **Bước 7**.

---

#### Nói nôm na

Bước 6 giống như **kiểm tra CMND** trước khi tin:
1. Có tem chống giả không? (validate chữ ký)
2. Có đúng tên mình không? (check `aud` = client_id)
3. Có còn hạn không? (check `exp`)
4. Có đúng số seri không? (check `nonce`)

Chỉ khi qua hết các bước này, backend mới extract thông tin từ CMND và chuyển cho nhân viên xử lý tiếp (Bước 7).

→ Sau bước này, Spring Security có `OidcUser` với các claims hợp lệ, sẵn sàng cho `CustomOidcUserService` (Bước 7).

---

### Bước 7: `CustomOidcUserService.loadUser()` — tìm/tạo user trong DB local từ thông tin Google

Đây là bước quan trọng nhất trong OIDC flow. Spring Security gọi `CustomOidcUserService.loadUser()` sau khi validate ID Token xong (Bước 6).

Service này làm **4 việc** theo thứ tự:
1. Gọi Google UserInfo API lấy claims chi tiết
2. Validate claims — chặn account-takeover
3. Tìm user trong DB local theo email
4. Phân nhánh: user mới → tạo mới; user cũ → cập nhật + link

---

#### Việc 1: `super.loadUser(userRequest)` — gọi Google lấy claims

```java
OidcUser oidcUser = super.loadUser(userRequest);
```

**`userRequest` là gì?** Spring Security truyền vào một `OidcUserRequest` chứa:
- `ClientRegistration` — thông tin app (client_id, client_secret, scope...) — lấy từ `application.yml`
- `AccessToken` — access token Google vừa trả về ở Bước 5 (dùng để gọi UserInfo API)

**`super.loadUser()` làm gì?** Đây là HTTP call (server-to-server) đến Google UserInfo endpoint, dùng `access_token` (lấy từ response Bước 5) để xác thực:
```
GET https://openidconnect.googleapis.com/v1/userinfo
Authorization: Bearer ya29...   ← access_token Google trả ở Bước 5
```
Nhớ lại Bước 5, Google trả về cả `access_token` và `id_token`. `id_token` dùng để validate + lấy claims cơ bản, còn `access_token` được Spring Security giữ lại và gửi lên UserInfo endpoint này để lấy thêm chi tiết (picture URL chất lượng cao hơn, name đầy đủ...).

Đây cũng là request server-to-server (từ backend đến Google), không qua trình duyệt → an toàn.
Google trả về JSON với các claims chi tiết hơn ID Token:

| Claim | Ý nghĩa | Giá trị ví dụ | Note |
|-------|---------|---------------|------|
| `sub` | Google Subject ID — duy nhất cho mỗi Google account | `"1234567890"` | Không đổi, dùng để link account |
| `email` | Email Google | `"John.Doe@Gmail.com"` | Có thể viết hoa/thường |
| `email_verified` | Google đã xác thực email chưa? | `true` | ⚠️ Kiểu `Object` — có thể là Boolean hoặc String tuỳ API version |
| `name` | Tên hiển thị | `"John Doe"` | Dùng làm fullName mặc định |
| `picture` | Avatar URL Google | `"https://lh3.googleusercontent.com/a/..."` | Dùng làm avatar nếu user chưa có |

> ⚠️ **Service KHÔNG có `@Transactional`** — `super.loadUser()` gọi HTTP đến Google, giữ DB connection chờ network response là anti-pattern (có thể làm cạn connection pool).

---

#### Việc 2: Validate claims — chặn account-takeover

```java
// Lấy claims từ response — kiểu Object, cần cast
String rawEmail = (String) claims.get("email");
Object emailVerifiedObj = claims.get("email_verified");
String sub = (String) claims.get("sub");
String name = (String) claims.get("name");
String picture = (String) claims.get("picture");
```

```java
// 2a. Check email null/blank
if (rawEmail == null || rawEmail.isBlank())
    throw OAuth2AuthenticationException("email_not_provided");
```
> Chặn: nếu Google account không có email (rare) → không cho login.

```java
// 2b. Check email_verified — dùng Boolean.TRUE.equals(), KHÔNG dùng == hay !
if (!Boolean.TRUE.equals(emailVerifiedObj))
    throw OAuth2AuthenticationException("email_not_verified");
```
> **Tại sao phải dùng `Boolean.TRUE.equals()` thay vì `== true` hay `!emailVerifiedObj`?**
> Vì `email_verified` từ Google có thể trả về kiểu `Boolean` (true/false) hoặc `String` ("true"/"false") tuỳ API endpoint. Dùng `Boolean.TRUE.equals(obj)` an toàn: nếu obj là String "true" → trả false (không phải Boolean.TRUE), nếu obj là Boolean.FALSE → trả false. Chỉ trả true khi obj là `Boolean.TRUE`.
> 
> Nếu không check, attacker có thể tạo Google account không verify email → dùng để login vào account victim đã tồn tại trong hệ thống.

```java
// 2c. Chuẩn hóa email — lower case + trim
String email = rawEmail.toLowerCase().trim();
// John.Doe@gmail.com → john.doe@gmail.com
// Khớp format email register local — tránh duplicate account do khác case
```
> **Tại sao?** Email trong DB local đã được lowercased khi register. Google trả về `John.Doe@gmail.com` → nếu không lowercase, tìm user sẽ miss.

```java
// 2d. Tìm user trong DB — JOIN FETCH roles để tránh LazyInitializationException
User user = userRepository.findByEmailWithRoles(email).orElse(null);
```
> **JOIN FETCH roles để làm gì?** `User` entity có `@ManyToMany(fetch = LAZY)` với `Role`. Nếu không fetch từ query, khi code gọi `user.getRoles()` (ở ngoài transaction), Hibernate sẽ throw `LazyInitializationException`. `findByEmailWithRoles()` dùng `JOIN FETCH r.roles` để load roles ngay trong 1 query.

---

#### Việc 3: Phân nhánh — user mới hay cũ?

Tuỳ `user` có `null` hay không, service rẽ 2 nhánh:

```
userRepository.findByEmailWithRoles(email)
    │
    ├── null → 🟢 Nhánh A: User mới
    │
    └── not null → 🔵 Nhánh B: User đã tồn tại
```

---

#### 🟢 Nhánh A: Tạo user mới hoàn toàn

```java
// Tạo User entity
user = new User();
user.setUsername(generateUniqueUsername(email));  // Giải thích bên dưới
user.setPassword(null);  // KHÔNG đặt password — user Google chưa có mật khẩu (V15: password nullable)
user.setFullName(name);                           // Lấy từ profile Google
user.setAvatarUrl(picture);                       // Avatar từ Google
user.setActive(true);                             // Google đã verify → active ngay
user.setAuthProvider(AuthProvider.GOOGLE);        // Đánh dấu: login qua Google
user.setSocialId(sub);                            // Link Google Subject ID — để lần sau nhận diện
user.getRoles().add(roleRepository.findByName(USER));  // Mặc định role USER

// Mỗi user cần có Employee profile
Employee employee = new Employee();
employee.setUser(user);

userRepository.save(user);
employeeRepository.save(employee);   // Save Employee riêng vì không cascade ALL
```

> **`generateUniqueUsername()` làm gì?**
> ```java
> String base = email.split("@")[0];              // john.doe@gmail.com → "john.doe"
> base = base.replaceAll("[^a-zA-Z0-9_]", "_"); // john.doe → "john_doe"
> String username = base;
> int suffix = 1;
> while (userRepository.findByUsername(username).isPresent()) {  // Nếu "john_doe" đã tồn tại
>     username = base + suffix;                        // → "john_doe1", "john_doe2"...
>     suffix++;
> }
> return username;
> ```
> Mục đích: tạo username từ email, nếu trùng thì thêm số. Không cho user tự chọn (tránh xung đột).

> **Vì sao `password = null`?** Migration V15 đã cho phép cột `password` NULL (bỏ NOT NULL). User Google không có mật khẩu → `null` nghĩa là "chưa đặt mật khẩu". Hệ quả:
> - Không ai login bằng email/password được cho đến khi user tự đặt qua `POST /auth/change-password` (lần đầu chỉ cần `newPassword`, không cần oldPassword).
> - `AuthResponse.hasPassword = false` → FE hiện form "đặt mật khẩu lần đầu" (2 field) thay vì "đổi mật khẩu" (3 field).
> - ❌ Trước đây dùng `UUID` hash để "lấp chỗ trống" cho constraint NOT NULL — anti-pattern, đã loại bỏ ở migration V15.

> **Nếu user xoá Google account sau này thì sao?** Không login bằng Google được nữa. Nhưng nếu user đã đặt mật khẩu từ trước (qua change-password) thì vẫn login bằng email/password bình thường. Nếu chưa từng đặt → cần hỗ trợ manual để đặt lại mật khẩu.

---

#### 🔵 Nhánh B: User đã tồn tại

Service kiểm tra lần lượt các điều kiện theo thứ tự — mỗi bước có thể dừng lại hoặc tiếp tục:

| Bước | Điều kiện | Hành động | Giải thích |
|------|-----------|-----------|------------|
| 1 | `user.isDeleted()` | ❌ **Từ chối** — throw exception → `OidcLoginFailureHandler` trả 403 | User bị banned/admin khoá — không cho login bằng Google để lách |
| 2 | `!user.isActive()` | ✅ **Auto-activate** — `user.setActive(true)` | User register bằng OTP nhưng chưa verify email. Login Google chứng minh email thật → tự động kích hoạt. Xoá cả OTP code cũ trong Redis cho sạch |
| 3 | Có `pendingAccountType`? | ✅ **Xử lý pending** — nếu `EMPLOYER` thì tạo Company; nếu `USER` thì tạo Employee nếu chưa có | User register local chọn "Tôi là nhà tuyển dụng" nhưng chưa verify OTP. Login Google → hoàn tất luôn quy trình đăng ký |
| 4 | `socialId == null` | ✅ **Link Google** — `user.setSocialId(sub)` | User trước đây register bằng password, chưa từng link Google. Giờ login Google → link vào cùng 1 account. Cho phép login cả password lẫn Google |
| 5 | `avatarUrl == null` | ✅ Set `avatarUrl = picture` từ Google | User chưa có avatar → lấy từ Google |
| 6 | Có thay đổi nào không? | ✅ `userRepository.save(user)` | **Chỉ save khi có thay đổi** — tránh update vô ích làm thay đổi `updated_at` |

> **Tại sao KHÔNG ghi đè `authProvider`?** Nếu user đã register local (authProvider = LOCAL), login Google lần đầu chỉ thêm `socialId`, không đổi authProvider. 1 tài khoản có 2 cách login — linh hoạt. Nếu ghi đè thành GOOGLE, user sẽ không login bằng password được nữa.
>
> **Tại sao thứ tự kiểm tra quan trọng?** Bước 1 (deleted) phải check trước — nếu banned thì không cho qua. Bước 2 (inactive) check tiếp — nếu chưa active thì activate + xử lý pending. Bước 3-5 chỉ áp dụng cho user đang active.

---

#### Việc 4: Trả về `CustomOidcUser` cho Spring Security

```java
// Chuyển roles thành authorities
List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
    .map(role -> new SimpleGrantedAuthority(role.getName().getAuthority()))
    .toList();

// Tạo CustomOidcUser — cầu nối giữa Google và hệ thống local
return new CustomOidcUser(
    authorities,              // ROLE_USER / ROLE_COMPANY — để Spring Security phân quyền
    oidcUser.getIdToken(),    // ID Token gốc từ Google — giữ nguyên
    oidcUser.getUserInfo(),   // UserInfo từ Google — giữ nguyên
    user.getId(),             // 🔑 userId local — quan trọng nhất cho Bước 8
    user.getEmail()           // Email để tiện dùng, tránh get từ DB lại
);
```

> **`CustomOidcUser` đóng vai trò gì?** Nó kế thừa `DefaultOidcUser` (chuẩn của Spring Security) + thêm `userId` local. `OidcLoginSuccessHandler` ở Bước 8 sẽ đọc `userId` này từ authentication object để tạo ticket.
>
> Nếu không có `CustomOidcUser`, handler phải parse claims từ ID Token để tìm email → query DB lại để lấy userId — dư 1 lần query không cần thiết.

---

#### 🔄 Sau khi `loadUser()` trả về — Spring Security tự động làm gì?

Sau khi `CustomOidcUserService.loadUser()` trả về `CustomOidcUser`, Spring Security **tự động** làm những việc sau — không cần code tay:

**1. Gói vào `OAuth2LoginAuthenticationToken`**

`OAuth2LoginAuthenticationProvider` (provider mặc định của Spring Security) nhận `CustomOidcUser` từ `loadUser()`, gói nó vào:

```java
OAuth2LoginAuthenticationToken authentication =
    new OAuth2LoginAuthenticationToken(
        clientRegistration,                                    // ClientRegistration từ config
        oidcUserRequest.getAuthorizationExchange(),            // Authorization exchange (request + response)
        customOidcUser,                                        // ← CustomOidcUser của mình (Principal)
        customOidcUser.getAuthorities(),                       // ROLE_USER / ROLE_COMPANY
        clientRegistration.getClientId()                       // clientId của app
    );
authentication.setAuthenticated(true);                         // Đánh dấu đã xác thực
```

**2. Đặt vào `SecurityContextHolder`**

`OAuth2LoginAuthenticationFilter` (filter nhận callback Google từ Bước 4) nhận token từ provider, rồi:

```java
SecurityContextHolder.getContext().setAuthentication(authentication);
// CustomOidcUser giờ có thể lấy ra từ bất kỳ đâu:
// - Trong filter chain hiện tại: SecurityContextHolder.getContext().getAuthentication()
// - Trong controller: @AuthenticationPrincipal CustomOidcUser user
// - Trong service: SecurityContextHolder.getContext().getAuthentication().getPrincipal()
// - Trong handler (Bước 8): authentication.getPrincipal()
```

**3. Gọi `OidcLoginSuccessHandler`** (hoặc `OidcLoginFailureHandler` nếu lỗi)

Filter gọi success handler, truyền authentication object (chứa `CustomOidcUser`) vào:

```java
successHandler.onAuthenticationSuccess(request, response, authentication);
// Trong handler:
//   OAuth2LoginAuthenticationToken auth = (OAuth2LoginAuthenticationToken) authentication;
//   CustomOidcUser oidcUser = (CustomOidcUser) auth.getPrincipal();
//   Long userId = oidcUser.getUserId();    ← lấy userId local
//   String email = oidcUser.getEmail();     ← lấy email
```

**4. Dọn dẹp state — gọi `removeAuthorizationRequest()`**

Sau khi success/failure handler chạy xong, filter gọi `RedisOAuth2AuthorizationRequestRepository.removeAuthorizationRequest()` trong `finally` block — dù thành công hay thất bại đều chạy:

```java
try {
    // Xử lý authentication + gọi handler
} finally {
    // LUÔN chạy, kể cả khi exception
    this.authorizationRequestRepository.removeAuthorizationRequest(request, response);
}
```

Hàm này làm 2 việc:
- `redisTemplate.delete(Oauth2Constant.STATE_PREFIX + stateId  // prefix "oauth2:state:" định nghĩa trong common/constant/Oauth2Constant)` — xoá `OAuth2AuthorizationRequest` khỏi Redis
- `writeCookie(request, response, null, 0)` — clear cookie `oauth2_state` (set maxAge = 0)

> **Tại sao cần dọn dẹp?**
> - State chỉ dùng 1 lần — nếu không xoá, ai đó có thể dùng lại state cũ để callback giả mạo (replay attack)
> - Cookie sống tới maxAge — nếu không clear, browser vẫn gửi cookie cũ cho request sau → gây rác
> - Dù làm trong `finally`, TTL 120s vẫn là lưới an toàn cuối cùng nếu có lỗi không mong muốn

> **Tóm lại:** Spring Security làm hết pipeline tự động:
> ```
> loadUser() trả về CustomOidcUser
>     → OAuth2LoginAuthenticationProvider gói vào token
>     → set vào SecurityContextHolder
>     → gọi success/failure handler
>     → finally: removeAuthorizationRequest() — dọn state Redis + cookie
> ```
> 
> `userId` local sẽ được `OidcLoginSuccessHandler` dùng ở Bước 8 để tạo ticket — không phải query DB lại.

---

### Bước 8: `OidcLoginSuccessHandler.onAuthenticationSuccess()` — tạo ticket + redirect về client

Spring Security gọi handler này Ngay sau khi `CustomOidcUserService.loadUser()` trả về thành công. Handler làm 3 việc:

---

**Việc 1: Tạo one-time ticket, lưu vào Redis**

```java
CustomOidcUser oidcUser = (CustomOidcUser) authentication.getPrincipal();
Long userId = oidcUser.getUserId();  // ← userId local từ DB, lấy từ Bước 7

String ticket = UUID.randomUUID().toString();
stringRedisTemplate.set(Oauth2Constant.TICKET_PREFIX + ticket, userId.toString(), 60s);
```

Ticket này giống như **tờ phiếu gửi xe**:
- Chỉ dùng 1 lần (atomic GETDEL ở Bước 11)
- Tự hủy sau 60 giây (TTL)
- Ai có ticket là có thể lấy token — nên handler chỉ log **8 ký tự đầu**

---

**Việc 2: Xác định đích redirect — về web hay mobile?**

```java
// Đọc state từ URL callback (?state=...)
String state = request.getParameter("state");

// Lấy return_url từ Redis — nếu là mobile đã gửi lên từ Bước 1
String returnUrl = (state != null)
    ? stringRedisTemplate.getAndDelete(Oauth2Constant.RETURN_PREFIX + state)  // GETDEL atomic
    : null;

// Quyết định redirect về đâu
String target;
if (returnUrl != null && isAllowedMobileScheme(returnUrl)) {
    target = returnUrl;                    // Mobile: về deep link, VD: findjob://oauth/callback
} else {
    target = frontendRedirectUrl;          // Web: về mặc định, VD: http://localhost:5173/oauth-callback
}

String redirectUrl = target + (target.contains("?") ? "&" : "?") + "ticket=" + ticket;
```

**Luồng quyết định:**

```
Có return_url trong Redis?            Validate whitelist?    → Redirect về
──────────────────────────────────────────────────────────────────────
  Có (tức là mobile)                   Hợp lệ (findjob://)   → Mobile deep link
  Có (mobile)                          Sai (http://evil.com)  → Fallback về web URL
  Không (tức là web)                   —                       → Web URL mặc định
```

> **Tại sao validate whitelist lần 2?** (defense in depth)
> `RedisOAuth2AuthorizationRequestRepository` đã validate scheme lúc lưu (Bước 1). Handler validate lại — phòng Redis bị ghi đè từ nguồn khác.

---

**Việc 3: Redirect trình duyệt về client**

```
response.sendRedirect(redirectUrl);
```

Kết quả: trình duyệt được redirect về URL có kèm ticket → Bước 9.

---

### Bước 9: Trình duyệt nhận redirect — FE đọc ticket từ URL

Sau Bước 8, backend trả về HTTP 302, trình duyệt tự động redirect về client. Cách nhận ticket khác nhau giữa web và mobile:

#### Web:
```
http://localhost:5173/oauth-callback?ticket=abc-123-def-456-...
```
React app nhận được URL này (thông qua React Router), parse query param `ticket`, lưu vào biến. Toàn bộ OIDC flow trên server đã xong — từ giờ chỉ cần đổi ticket lấy JWT.

> **Tại sao không trả thẳng JWT mà phải qua ticket?** Thực ra backend hoàn toàn có thể ghi JSON vào response body ngay tại `OidcLoginSuccessHandler`. Nhưng dùng ticket pattern là best practice của OAuth2 Authorization Code flow:
> - **Tách biệt concern:** OIDC callback flow (xử lý state, exchange code với Google) không dính với JWT creation (tạo session, set cookie)
> - **FE chủ động:** FE quyết định khi nào exchange — có thể kiểm soát timeout, retry nếu lỗi mạng
> - **Redirect là chuẩn OAuth2:** OAuth2 spec định nghĩa callback dùng redirect, không phải JSON response
>
> Ticket là cầu nối: redirect trình duyệt về FE kèm ticket → FE tự gọi API exchange để lấy token.

#### Mobile:
```
findjob://oauth/callback?ticket=abc-123-def-456-...
```
App mobile nhận deep link này thông qua `Linking.addEventListener()` (Expo) — parse URL, lấy ticket. Không cần refresh hay reload gì vì app vẫn chạy ngầm (in-app browser chỉ là overlay).

> **Web vs Mobile khác nhau thế nào ở bước này?**
> - Web: trang web bị reload hoàn toàn (full navigation từ Google → backend → web app). React app khởi tạo lại từ đầu.
> - Mobile: in-app browser chỉ là overlay — app chính vẫn chạy, sự kiện deep link được gửi về JavaScript. App parse ticket và gọi API ngay.

---

### Bước 10: FE gọi backend — exchange ticket lấy token

Dù web hay mobile, cách gọi API giống hệt nhau — POST request với ticket trong body:

```http
POST /api/v1/auth/exchange-ticket
Content-Type: application/json

{
  "ticket": "abc-123-def-456-..."   ← Ticket lấy từ URL ở Bước 9
}
```

> **Endpoint này có public không?** Có — nằm trong `PUBLIC_PATTERNS` của `SecurityConfig.java`, không yêu cầu JWT. Vì bản thân ticket đã là cơ chế xác thực: ai có ticket là đã được backend cấp phát từ Bước 8 (chỉ OIDC thành công mới có ticket).

---

### Bước 11: Backend xử lý exchange-ticket — đổi ticket lấy JWT

**Controller** nhận request, gọi service:

```java
AuthResponse response = authService.exchangeTicket(
    request.ticket(),                 // ticket từ FE
    requestUtils.getClientIp(req),    // client IP → ghi log audit
    requestUtils.getUserAgent(req),   // User-Agent → ghi log
    httpServletResponse               // để set refresh token cookie (HttpOnly)
);
```

---

#### Việc 1: `exchangeTicket()` — lấy userId từ ticket (atomic GETDEL)

```java
String redisKey = Oauth2Constant.TICKET_PREFIX + ticket;
String userIdStr = stringRedisTemplate.opsForValue().getAndDelete(redisKey);
//               ^^^^^^^^^^^^^^^^^^^^^^
//               GETDEL: đọc + xóa trong 1 lệnh Redis atomic

if (userIdStr == null) {
    throw new AppException(UNAUTHORIZED);
    // Ticket không tồn tại → hoặc hết hạn (TTL 60s), hoặc đã dùng rồi
}

Long userId = Long.parseLong(userIdStr);
```

Service dùng `getAndDelete()` — đây là lệnh Redis atomic tương đương `GET` + `DEL` trong 1 lần gọi:

| Cách làm | Vấn đề |
|----------|--------|
| `GET` rồi `DEL` | Nếu 2 request song song gửi cùng 1 ticket: cả 2 đều `GET` thấy ticket còn → cả 2 đều xem như hợp lệ → lỗi bảo mật |
| `GETDEL` (1 lệnh) | Atomic: thằng nào tới trước `GETDEL` được userId, thằng sau nhận `null` → an toàn tuyệt đối |

---

#### Việc 2: `createUserSession()` — tạo session + JWT + set cookie

`createUserSession()` là hàm dùng chung cho cả login thường và OIDC. Dịch vụ này làm lần lượt:

| Bước | Hành động | Mục đích |
|------|-----------|----------|
| 1 | `preLoginCheck(userId)` | Kiểm tra user tồn tại trong DB, không bị banned |
| 2 | Tạo `sessionId` (UUID) + build `CustomUserDetails` từ DB | Chuẩn bị dữ liệu cho JWT |
| 3 | Tạo JWT **access token** (ngắn hạn) + **refresh token** (dài hạn) | Token pair cho authentication |
| 4 | Lưu session vào Redis: `session:<sessionId>` → JSON hash | Server-side session để revoke được |
| 5 | Thêm session vào danh sách: `user:sessions:<userId>` → Set | Để sau này force logout tất cả thiết bị |
| 6 | Set **refresh token cookie**: HttpOnly, SameSite=Strict, 7 ngày | Web: trình duyệt tự động gửi, JS không đọc được. Mobile: không dùng cookie, RT trả trong body |
| 7 | Trả về `AuthResponse` — chứa access token + thông tin user | FE nhận và lưu access token |

> **Ghi chú cho OIDC:** `deviceId` được tự sinh UUID (khác với login thường — client tự gửi deviceId). `deviceName` set fixed = `"Google Login"` — để phân biệt trong danh sách session.

---

### Bước 12: Response trả về FE — hoàn tất login

Sau khi `createUserSession()` hoàn tất, backend trả về:

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "code": 4001,
    "id": 1,
    "username": "user@gmail.com",
    "roles": [
      { "id": 1, "name": "USER" }
    ],
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": null,     // mobile: có giá trị (trả trong body); web: null (dùng cookie)
    "hasPassword": false      // user Google chưa đặt mật khẩu
  }
}
```

**Cách web và mobile nhận refresh token khác nhau:**

| | Web | Mobile |
|---|---|---|
| **access token** | Trong response body `data.accessToken` | Giống web — trả trong body |
| **refresh token** | **Không trả trong body** (`null`). Set qua HTTP header `Set-Cookie` — HttpOnly, SameSite=Strict | **Trả trong body** `data.refreshToken` — app lưu vào `expo-secure-store` |
| **Tại sao khác?** | Trình duyệt tự động quản lý cookie — JS không cần động vào. An toàn hơn vì XSS không đọc được cookie | Mobile không có cơ chế cookie tự động như trình duyệt → phải trả trong body để app tự lưu |

> **Sau bước này, user đã đăng nhập thành công qua Google.** FE lưu `accessToken` (localStorage / secure store), gắn vào header `Authorization: Bearer <token>` cho các request sau. Khi token hết hạn, dùng `refreshToken` (cookie / secure store) để lấy token mới qua `/api/v1/auth/refresh-token`.

---

## 🔐 Chi tiết bảo mật

### 1. Email verification check
`CustomOidcUserService` check `email_verified` từ Google. Nếu email chưa verify → từ chối.
> Ngăn chặn attacker tạo Gmail không verify → chiếm account local.

### 2. One-time ticket + Atomic GETDEL
Ticket chỉ dùng được **1 lần duy nhất**. Dùng GETDEL (1 lệnh Redis atomic) → không race condition.
> Ngăn chặn replay attack: nếu ticket bị lộ, attacker chỉ dùng được 1 lần, và nạn nhân dùng sau sẽ bị từ chối.

### 3. Ticket TTL = 60s
Ticket tự hủy sau 60 giây. Giới hạn thời gian window cho attacker.
> Ngăn chặn slow attack: attacker có 60s để dùng ticket, nếu không → ticket chết.

### 4. Whitelist scheme (Open Redirect Protection) 🔥 (MỚI)
Cả `OidcLoginSuccessHandler` và `RedisOAuth2AuthorizationRequestRepository` đều validate scheme.
```java
return Arrays.stream(allowedMobileSchemes.split(","))
        .map(String::trim).filter(s -> !s.isEmpty())
        .anyMatch(url::startsWith);
```
> Default: chỉ `findjob://`. Cấu hình qua `app.oauth2.allowed-mobile-schemes`.
> Ngăn chặn attacker chèn URL độc → redirect user đến site giả mạo.

### 5. Defense in depth — Validate lần 2 (MỚI)
Ngay cả khi Redis đã lưu `return_url`, `OidcLoginSuccessHandler` validate lại scheme trước khi redirect.
> Phòng trường hợp Redis key bị ghi đè từ nguồn khác.

### 6. GETDEL cho cả ticket và return_url (MỚI)
Cả `oauth2:ticket:*` và `oauth2:return:*` đều dùng `getAndDelete()` — đọc + xóa atomic.
> Tránh replay: mỗi URL chỉ dùng được 1 lần.

### 7. State param chống CSRF 🔥 (MỚI)
Spring Security tự động tạo `state` param cho mỗi request OAuth2.
Khi Google callback, state được verify — nếu không khớp → từ chối.
> Ngăn chặn CSRF attack: attacker không thể tự ý link Google account vào victim.

### 8. Log không leak ticket
Chỉ log 8 ký tự đầu của ticket.
> Ngăn chặn log injection / credential leak qua log file.

### 9. password = NULL cho OIDC user (thay vì random hash)
User Google được tạo với `password = null` (migration V15 bỏ NOT NULL, dọn data cũ `UPDATE users SET password = NULL WHERE auth_provider = 'GOOGLE'`).
> Ngăn chặn: user OIDC không có mật khẩu → không thể login bằng email/password cho đến khi tự đặt qua `change-password` (lần đầu không cần oldPassword, `hasPassword = false`).
> Chỉ login được qua Google (hoặc qua password sau khi đã đặt).

### 10. HttpOnly + SameSite=Strict cookie
Refresh token được set trong cookie HttpOnly (không JS đọc được) + SameSite=Strict (không gửi trong cross-site request).
> Ngăn chặn XSS + CSRF attack.

### 11. Nonce validation (OpenID Connect) 🔥
Spring Security tự động tạo `nonce` param khi có `scope=openid`. Google trả về `nonce` claim trong ID Token (JWT). Spring Security validate nonce claim với nonce đã lưu.
> Ngăn chặn **ID Token replay attack**: nếu attacker đánh cắp ID Token, nonce claim không khớp → từ chối.

### 12. PKCE (Proof Key for Code Exchange) 🔥 (MỚI)
Bật PKCE qua `DefaultOAuth2AuthorizationRequestResolver` + `OAuth2AuthorizationRequestCustomizer.withPkce()`.
`code_verifier` (String) được lưu trong `OAuth2AuthorizationRequest.attributes`, serialized cùng request qua JDK vào Redis.
Khi Google gọi callback, Spring Security dùng `code_verifier` để exchange authorization code.
> Mặc dù backend là confidential client (đã có `client_secret`), PKCE vẫn là best practice — bảo vệ authorization code ngay cả khi secret bị leak.
> Không cần PKCE ở mobile flow vì mobile redirect qua backend (confidential client), không gọi Google trực tiếp.

### 13. KHÔNG ghi đè authProvider
Nếu user đã register local (authProvider=LOCAL), khi login Google lần đầu, giữ nguyên authProvider, chỉ link socialId.
> Cho phép user có cả 2 cách login (local + Google) trên cùng 1 tài khoản.

---

## ⚙️ Các edge cases đã xử lý

| # | Case | Kết quả |
|---|------|---------|
| 1 | User mới hoàn toàn | ✅ Tạo tài khoản active + Employee, login thành công |
| 2 | User local active, chưa từng link Google | ✅ Link Google vào tài khoản local, login thành công |
| 3 | User local inactive (chưa verify OTP) | ✅ Auto-activate + dọn OTP state, login thành công |
| 4 | User đã từng login Google | ✅ Login như bình thường (không mutation) |
| 5 | User bị banned (deleted = true) | ❌ `OidcLoginFailureHandler` trả về 403 (JSON / redirect) |
| 6 | Exchange ticket 2 lần | ❌ Lần 1 success, lần 2 401 Unauthorized |
| 7 | Ticket hết hạn (TTL 60s) | ❌ 401 Unauthorized |
| 8 | Google trả về email chưa verified | ❌ 401 "Email not verified by Google" |
| 9 | **Mobile: return_url trùng với whitelist** | ✅ Redirect về deep link `findjob://...` |
| 10 | **Mobile: return_url KHÔNG hợp lệ** | ✅ Fallback về web redirect (có thể fail, nhưng an toàn) |
| 11 | **Restart BE giữa flow** | ✅ Redis còn state (TTL 120s) → callback vẫn hoạt động |
| 12 | **PKCE code_verifier serialization** | ✅ code_verifier là String trong attributes → JDK serialization OK |
| 13 | **Nonce validation khi login lại** | ✅ nonce mới mỗi lần, Spring Security tự động |

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

## 📊 So sánh: Web vs Mobile OIDC

| Tiêu chí | Web (React SPA) | Mobile (React Native) |
|----------|----------------|----------------------|
| Cách gọi OIDC | `window.location.href` — full navigation | `WebBrowser.openAuthSessionAsync()` — in-app browser |
| Trạng thái FE | Biến mất (tab chuyển trang) | Vẫn chạy (in-app browser overlay) |
| `return_url` | ❌ Không cần — backend biết sẵn | ✅ **Cần** — backend không biết deep link |
| Nhận ticket | URL param `?ticket=xxx` | Deep link `findjob://oauth/callback?ticket=xxx` |
| Nhận lỗi | JSON response | Deep link `findjob://oauth/callback?error=3008` |
| Nhận refresh token | Cookie HttpOnly (trình duyệt tự lo) | Response body → lưu `expo-secure-store` |
| State lưu ở | Redis (cookie `oauth2_state`) | Redis (cookie `oauth2_state` — gửi qua in-app browser) |

---

## 🔀 Phân biệt `redirect_uri` vs `return_url`

Có **2 lần redirect** trong OIDC flow. Nhiều người nhầm tưởng chỉ có 1, hoặc gộp chung làm 1:

| Lần | Từ → Đến | Dùng tham số nào? | Cố định? |
|-----|----------|-------------------|----------|
| **1** | **Google → Backend** | `redirect_uri` (OAuth chuẩn) | ✅ Cố định, cả web & mobile giống nhau |
| **2** | **Backend → Client (Web/Mobile)** | `app.oauth2.redirect-url` (web) / `return_url` (mobile) | Web cố định, mobile động |

---

### Lần 1: Google → Backend (dùng `redirect_uri`)

```
User click "Login with Google"
       ↓
Backend redirect user sang accounts.google.com (login page)
       ↓
User đăng nhập xong
       ↓
Google redirect về: http://localhost:8080/login/oauth2/code/google
                    ↑                                  ↑
                Backend của mình               Cái redirect_uri này
```

| | |
|---|---|
| **Mục đích** | Google gửi authorization code **về backend** sau khi user login thành công |
| **Giá trị** | `http://localhost:8080/login/oauth2/code/google` — cố định cho cả web lẫn mobile |
| **Ai cấu hình** | Backend — phải khai báo trong Google Cloud Console whitelist (exact match) |
| **Ai thấy** | Chỉ Backend và Google. **FE không thấy, không sửa được.** |
| **Bảo mật** | Google check exact match — nếu sai URL là từ chối. Chống interception attack. |

> **Nói nôm na:** `redirect_uri` = **số điện thoại của backend** — Google gọi về số này, ai cũng gọi chung 1 số.

---

### Lần 2: Backend → Client (Web hoặc Mobile)

Backend xử lý code xong, tạo ticket xong → giờ cần redirect browser **về client** để FE đọc ticket.

#### Web:
```
Backend redirect về: http://localhost:5173/oauth-callback?ticket=xxx
                     ↑                                        ↑
                 Web React (localhost:5173)              Ticket đổi lấy token
```
- Backend **biết sẵn** web chạy ở `localhost:5173` → hardcode trong `application.yml`
- Tham số: `app.oauth2.redirect-url`
- **Không cần web gửi lên** — backend tự biết

#### Mobile:
```
Backend redirect về: findjob://oauth/callback?ticket=xxx
                     ↑
                 Mobile app (deep link, thay đổi theo thiết bị)
```
- Backend **không biết trước** mobile đang chạy ở URL nào → **mobile phải nói cho backend biết**
- Tham số: `return_url` — mobile gửi lên mỗi request
- `return_url` = phiên bản động của `app.oauth2.redirect-url`

| | Web | Mobile |
|---|---|---|
| **Backend biết URL client ở đâu?** | Hardcode trong config | Mobile gửi lên qua param `return_url` |
| **Tên tham số** | `app.oauth2.redirect-url` | `return_url` |
| **Giá trị ví dụ** | `http://localhost:5173/oauth-callback` | `findjob://oauth/callback` hoặc `exp://192.168.1.5:8081/...` |
| **Tại sao khác?** | Web chỉ 1 URL duy nhất → hardcode được | Mobile nhiều môi trường → phải động |

> **Nói nôm na:**
> - `app.oauth2.redirect-url` (web) = **backend biết nhà web ở đâu, tự chạy tới**
> - `return_url` (mobile) = **"mày đang ở đâu, tao chạy tới" — mobile tự nói**

---

### Ví dụ 1 request có cả 2 redirect

```
Mobile gửi request:
  GET /oauth2/authorization/google?return_url=findjob://oauth/callback
                                    ↑
                          Lần 2: backend redirect về đâu? (mobile nói)

Backend thêm redirect_uri vào URL gửi Google:
  GET https://accounts.google.com/o/oauth2/v2/auth?
    client_id=xxx&
    redirect_uri=http://localhost:8080/login/oauth2/code/google&   ← Lần 1: Google gửi code về đâu?
    state=xxx&
    ...
```

### Sơ đồ luồng

```
                    Lần 1: Google → Backend (redirect_uri)
                    ┌─────────────────────────────────────┐
                    │                                     │
                    ▼                                     │
  Mobile ──→ Backend ──→ Google ──→ Backend ──→ Mobile
                    ↑                                     │
                    │        Lần 2: Backend → Client      │
                    └─────────────────────────────────────┘
                        (return_url / app.oauth2.redirect-url)
```

---

### Tại sao mobile không hardcode luôn return_url như web?

Web chỉ có **1 URL duy nhất** (`localhost:5173`) — backend hardcode được.

Mobile có **nhiều URL khác nhau** tuỳ môi trường:

| Môi trường | Deep link callback |
|------------|-------------------|
| Standalone build (production) | `findjob://oauth/callback` |
| Expo Go (iPhone vật lý) | `exp://192.168.1.5:8081/--/oauth/callback` |
| Expo Go (Android emulator) | `exp://10.0.2.2:8081/--/oauth/callback` |
| iOS simulator | `exp://localhost:8081/--/oauth/callback` |
| Development build | `findjobdev://oauth/callback` |

→ 1 scheme không thể đại diện hết. Nếu hardcode `findjob://` thì dev trên Expo Go không chạy được.

**Giải pháp:** Mobile tự tạo URL đúng cho thiết bị hiện tại qua `Linking.createURL()`, gửi lên backend qua param `return_url`. Backend chỉ việc validate whitelist scheme để đảm bảo an toàn.

---

## 📝 Sequence Diagram (dạng text) — Web

```
Web Browser         Backend              Google             Redis
  │                    │                    │                 │
  │─ GET /oauth2/authorization/google ─────►│                 │
  │                    │─ Lưu state + cookie ────────────────►│
  │                    │─ 302 Redirect ────►│                 │
  │◄─ 302 ─────────────│                    │                 │
  │                    │                    │                 │
  │─ Login + consent ──────────────────────►│                 │
  │                    │◄─ Auth code ───────│                 │
  │                    │                    │                 │
  │                    │─ Cookie → Load state ◄───────────────│
  │                    │─ POST /token ─────►│                 │
  │                    │◄─ ID Token ────────│                 │
  │                    │                    │                 │
  │                    │─ loadUser() ───────│                 │
  │                    │  (validate +       │                 │
  │                    │   find/create user)│                 │
  │                    │                    │                 │
  │                    │─ set(ticket, userId, 60s) ──────────►│
  │                    │─ GETDEL(return) → null (ko có mobile)│
  │                    │                    │                 │
  │◄─ 302 /oauth-callback?ticket=xxx ───────│                 │
  │                    │                    │                 │
  │─ POST /exchange-ticket ────────────────►│                 │
  │                    │─ GETDEL(ticket) ────────────────────►│
  │                    │◄─ userId ────────────────────────────│
  │                    │─ createSession() ───────────────────►│
  │◄─ AuthResponse ────│                    │                 │
```

## 📝 Sequence Diagram (dạng text) — Mobile

```
Mobile App          Backend              Google             Redis
  │                    │                    │                 │
  │─ openAuthSessionAsync(/oauth2/authorization/google        │
  │  ?return_url=findjob://oauth/callback)  │                 │
  │──────────────────►│                    │                  │
  │                    │─ Lưu state + cookie ────────────────►│
  │                    │─ Lưu oauth2:return:{state}=return_url ──►│
  │                    │─ 302 Redirect ────►│                 │
  │ (in-app browser)   │                    │                 │
  │◄─ Google login page│                    │                 │
  │                    │                    │                 │
  │─ Login + consent ──────────────────────►│                 │
  │                    │◄─ Auth code ───────│                 │
  │                    │                    │                 │
  │                    │─ Load state + cookie ◄───────────────│
  │                    │─ POST /token ─────►│                 │
  │                    │◄─ ID Token ────────│                 │
  │                    │                    │                 │
  │                    │─ loadUser() ───────│                 │
  │                    │                    │                 │
  │                    │─ set(ticket, userId, 60s) ──────────►│
  │                    │─ GETDEL(oauth2:return:{state}) ◄─────│
  │                    │  → findjob://oauth/callback          │
  │                    │  → validate whitelist ✓              │
  │                    │                    │                 │
  │◄─ 302 findjob://oauth/callback?ticket=xxx                 │
  │                    │                    │                 │
  │─ (app nhận deep link, đọc ticket)       │                 │
  │─ POST /exchange-ticket ────────────────►│                 │
  │                    │─ GETDEL(ticket) ────────────────────►│
  │                    │◄─ userId ────────────────────────────│
  │                    │─ createSession() ───────────────────►│
  │◄─ AuthResponse ────│                    │                 │
  │  (lưu AT + RT vào SecureStore)          │                 │
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

app:
  oauth2:
    # URL redirect cho Web
    redirect-url: ${APP_OAUTH2_REDIRECT_URL:http://localhost:5173/oauth-callback}
    # Danh sách scheme mobile được phép nhận redirect, phân cách bằng dấu phẩy
    # findjob:// cho production, exp:// cho Expo Go dev
    allowed-mobile-schemes: ${APP_OAUTH2_ALLOWED_MOBILE_SCHEMES:findjob://}
```

### `RedisConfig.java` — `oauth2StateRedisTemplate`
```java
@Bean
public RedisTemplate<String, OAuth2AuthorizationRequest> oauth2StateRedisTemplate(
        RedisConnectionFactory factory) {
    RedisTemplate<String, OAuth2AuthorizationRequest> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    
    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    JdkSerializationRedisSerializer jdkSerializer = new JdkSerializationRedisSerializer();
    
    template.setKeySerializer(stringSerializer);
    template.setValueSerializer(jdkSerializer);
    // ...
    return template;
}
```
> Dùng JDK serialization vì `OAuth2AuthorizationRequest` không compatible với Jackson.
> Chỉ dùng cho data tạm (TTL 120s) — binary là chấp nhận được.

### `SecurityConfig.java` — wiring
```java
.oauth2Login(oauth2 -> oauth2
    .authorizationEndpoint(auth -> auth
        .authorizationRequestRepository(cookieRepo)                  // ← Redis
        .authorizationRequestResolver(pkceResolver(clientRegistrationRepo))) // ← PKCE + nonce
    .userInfoEndpoint(userInfo -> userInfo
        .oidcUserService(customOidcUserService))
    .successHandler(oidcLoginSuccessHandler)
    .failureHandler(oidcLoginFailureHandler)
)
```

---

## 🗄️ Redis keys

| Key pattern | Value | TTL | Serialization | Ghi chú |
|------------|-------|-----|---------------|---------|
| `oauth2:state:<stateId>` | `OAuth2AuthorizationRequest` | 120s | **JDK** | State lưu cookie `oauth2_state` |
| `oauth2:return:<state>` | `return_url` (String) | 120s | String | return_url cho mobile |
| `oauth2:ticket:<uuid>` | `userId` (String) | 60s | String | One-time exchange ticket |
| `session:<sessionId>` | Hash (username, deviceId, refreshJti, ...) | 7 ngày | JSON | Session info |

> **Nguồn gốc prefix trong code:** `oauth2:*` → `Oauth2Constant`; `session:`/`user:sessions:` → private trong `SessionServiceImpl`; `otp:*` → `OtpConstant`; `pending:*` → `PendingTokenConstant` (đều ở `common/constant/`). Đây là literal thật trong Redis — không đổi khi đổi tên constant.
| `user:sessions:<userId>` | Set of sessionId | Vô hạn | String | Danh sách session của user |

---

## 🧪 Test flow (bằng HTTP file)

```http
### Bước 1: Mở URL này trong trình duyệt (Web)
### http://localhost:8080/oauth2/authorization/google
###
### HOẶC với return_url (Mobile):
### http://localhost:8080/oauth2/authorization/google?return_url=findjob://oauth/callback

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

> **Xem thêm:**
> - [02. Tại sao backend vẫn có thể redirect về React SPA](./02-why-backend-redirect.md) — giải thích cơ chế HTTP redirect
> - [03. Mobile OIDC Flow](./03-mobile-oidc-flow.md) — chi tiết mobile flow với deep link
> - [Spring Security OAuth2 Client](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html)
> - [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
