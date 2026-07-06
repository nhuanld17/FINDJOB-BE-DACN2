# JwtAuthFilter — Gác cổng Access Token

> Không phải tài liệu cho một endpoint, mà cho **một bộ lọc** chạy trước **mọi request** tới
> path không nằm trong `PUBLIC_PATTERNS` — nơi thực sự quyết định "access token này có được dùng
> để xác thực request hay không".
>
> Bám sát code: `JwtAuthFilter` + `SecurityConfig` + `JwtAuthEntryPoint` +
> `JwtAccessDeniedHandler` + `CustomAuthException` + `UserDetailsServiceImpl.loadByUsername`.
> Cập nhật: 2026-07-05.
>
> 📎 Doc này giả định đã đọc `3. Kiến trúc Session & Token.md` (claim JWT, `session:{}`,
> `blacklist:*`) và nên đối chiếu với guard chain của `4. Refresh token.md` — hai bộ lọc rất
> giống nhau nhưng có khác biệt quan trọng (xem §5).

## Mục lục
1. [Vị trí trong Security Filter Chain](#1-vị-trí-trong-security-filter-chain)
2. [`shouldNotFilter` — bỏ qua endpoint public](#2-shouldnotfilter--bỏ-qua-endpoint-public)
3. [Guard chain chi tiết](#3-guard-chain-chi-tiết)
4. [Xử lý lỗi: `CustomAuthException` + 2 handler](#4-xử-lý-lỗi-customauthexception--2-handler)
5. [So sánh với guard chain của Refresh Token](#5-so-sánh-với-guard-chain-của-refresh-token)
6. [Response](#6-response)
7. [Bảng tra mã](#7-bảng-tra-mã)
8. [Ghi chú & điểm dễ nhầm](#8-ghi-chú--điểm-dễ-nhầm)

---

## 1. Vị trí trong Security Filter Chain

`SecurityConfig` đăng ký `jwtAuthFilter` bằng
`addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`, và
`sessionCreationPolicy(STATELESS)` — Spring Security **không** tự quản session HTTP, toàn bộ việc
"ai đang là ai" trong request phụ thuộc vào việc filter này có set được `Authentication` vào
`SecurityContextHolder` hay không.

```
Request
  │
  ▼
JwtAuthFilter (OncePerRequestFilter)
  │
  ├─ path public?        ──► bỏ qua filter, đi thẳng tiếp
  │
  ├─ không có Bearer token? ──► bỏ qua filter (KHÔNG set lỗi ở đây!),
  │                              để request đi tiếp KHÔNG có Authentication
  │
  └─ có Bearer token → chạy full guard chain (§3)
        ├─ hợp lệ   → set Authentication → đi tiếp
        └─ không hợp lệ → JwtAuthEntryPoint trả lỗi ngay, DỪNG chuỗi filter
  │
  ▼
authorizeHttpRequests (anyRequest().authenticated())
  │
  ├─ có Authentication trong context → cho vào controller
  └─ không có (vd token thiếu/không gửi) → JwtAuthEntryPoint.commence() với
                                            AuthenticationException MẶC ĐỊNH (không phải
                                            CustomAuthException) → 401 KHÔNG có `code`
```

Điểm quan trọng: **thiếu token hoàn toàn** và **có token nhưng sai** đi theo **2 đường khác
nhau** để tới cùng một `JwtAuthEntryPoint` — một đường có `code` (từ `CustomAuthException`), một
đường không (xem §4, §7).

---

## 2. `shouldNotFilter` — bỏ qua endpoint public

```java
private final RequestMatcher publicEndpointsMatcher = new OrRequestMatcher(SecurityConfig.publicMatchers());

protected boolean shouldNotFilter(HttpServletRequest request) {
    return publicEndpointsMatcher.matches(request);
}
```

Dùng lại đúng `SecurityConfig.PUBLIC_PATTERNS` (`/api/v1/auth/**`, `/products/**`,
`/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/health`) — **một nguồn duy nhất** cho cả việc
"path nào không cần đăng nhập" (`authorizeHttpRequests().permitAll()`) lẫn "path nào bộ lọc JWT
bỏ qua". Nhờ vậy **không thể lệch pha** giữa 2 nơi cấu hình (không có kiểu path được permitAll
nhưng filter vẫn chạy, hay ngược lại).

> Hệ quả trực tiếp: **`login`, `register`, `refresh-token`, `logout` đều KHÔNG đi qua
> `JwtAuthFilter`** — chúng tự lo phần xác thực của riêng mình (so mật khẩu, so `jti`...), như đã
> mô tả ở các doc 1/2/4/5.

---

## 3. Guard chain chi tiết

Áp dụng cho path **không** public và request **có** header `Authorization: Bearer <token>`
(nếu không có Bearer token, filter bỏ qua hoàn toàn — xem lưu ý ở §8).

```
1. parse claim (sub/jti/sessionId/deviceId) từ accessToken
     ├─ hết hạn (ExpiredJwtException)   → commence 3015 ACCESS_TOKEN_EXPIRED
     └─ lỗi khác (sai chữ ký, rác...)   → commence 3001 UNAUTHENTICATED

2. jti nằm trong blacklist:access:*?    → commence 2010 TOKEN_REVOKED

3. HGETALL session:{sessionId} rỗng?    → commence 3012 SESSION_INACTIVE

4. session.status != ACTIVE?            → commence 3012 SESSION_INACTIVE

5. session.username != sub (token)?     → commence 3001 UNAUTHENTICATED

6. session.deviceId != deviceId (token)? → commence 3016 SESSION_DEVICE_MISMATCH

   ── chỉ chạy nếu SecurityContextHolder CHƯA có Authentication ──
7. load CustomUserDetails theo username (DB tươi, JOIN roles)
     ├─ user.isDeleted()   → commence 2007 ACCOUNT_BANNED
     ├─ !user.isActive()   → commence 2005 USER_INACTIVE
     ├─ isTokenValid(token, userDetails) == false → commence 3001 UNAUTHENTICATED
     └─ hợp lệ → set Authentication vào SecurityContextHolder
                 + update session.lastSeen

8. filterChain.doFilter(request, response) — cho request đi tiếp
```

| Bước | Guard | Trả lời câu hỏi |
|:--:|---|---|
| 1 | Chữ ký & hạn dùng | "Đây có phải AT hợp lệ, còn hạn, do server ký?" |
| 2 | Blacklist | "AT này đã bị thu hồi chủ động (qua logout) chưa?" |
| 3–4 | Session tồn tại & active | "Phiên đứng sau AT này còn sống không?" |
| 5 | username khớp | "AT này có đúng là của user đang đứng tên session không?" |
| 6 | deviceId khớp | "AT này có đang bị dùng từ đúng thiết bị đã login không?" |
| 7 | User ở tầng DB (tươi) | "User có bị ban/deactivate **sau khi** AT đã được cấp không?" |

`commence(...)` gọi `authenticationEntryPoint.commence(request, response, ex)` — tương đương
"ném lỗi và dừng filter chain ngay", khác cơ chế `throw AppException` của tầng service (vì filter
chạy **trước** `DispatcherServlet`, không có `GlobalExceptionHandler` nào bắt được).

---

## 4. Xử lý lỗi: `CustomAuthException` + 2 handler

`SecurityConfig` khai 2 handler cho `exceptionHandling`:

- **`authenticationEntryPoint` → `JwtAuthEntryPoint`**: xử lý mọi `AuthenticationException`
  (chưa xác thực được / xác thực thất bại) → luôn trả **401**.
- **`accessDeniedHandler` → `JwtAccessDeniedHandler`**: xử lý `AccessDeniedException` (đã xác
  thực, nhưng **thiếu quyền**, ví dụ `@PreAuthorize` chặn role) → luôn trả **403**.

`CustomAuthException` là một `AuthenticationException` **mang thêm `ErrorCode`** — mọi lệnh
`commence(...)` trong `JwtAuthFilter` đều bọc lỗi vào class này trước khi gọi entry point:

```java
authenticationEntryPoint.commence(request, response,
        new CustomAuthException(ErrorCode.SESSION_INACTIVE));
```

`JwtAuthEntryPoint.commence(...)` rẽ 2 nhánh:

| Loại exception | Response |
|---|---|
| `CustomAuthException` (từ `JwtAuthFilter`) | `ErrorResponse.of(status, errorCode.getCode(), errorCode.getMessage())` — **có `code`**, status lấy từ chính `ErrorCode` (401 hoặc 403 tùy mã) |
| `AuthenticationException` khác (Spring Security tự ném khi **không có Authentication mà endpoint yêu cầu**, hoặc `UsernameNotFoundException` từ bước 7 lọt qua `catch (AuthenticationException ex)`) | `ErrorResponse.of(401, message)` — **KHÔNG có `code`**, status luôn cứng 401 |

`JwtAccessDeniedHandler` (403, thiếu quyền) dùng **format hoàn toàn khác** — không phải
`ErrorResponse`, mà một `Map` tay: `{"status":403, "error":"Forbidden", "message":"Access
Denied"}` — xem điểm bất nhất ở §8.

---

## 5. So sánh với guard chain của Refresh Token

`JwtAuthFilter` và `refreshToken()` (doc 4) trông rất giống nhau (đều: parse claim → blacklist →
session tồn tại → status ACTIVE → user DB → hợp lệ) nhưng có **3 khác biệt cốt lõi**:

| | `JwtAuthFilter` (Access Token) | `refreshToken()` (Refresh Token) |
|---|---|---|
| **So khớp "bản hiện hành"** | Không có — AT không được rotate, không có field `accessJtiCurrent` nào để so | **Có** — so `jti` với `session.refreshJtiCurrent` → nền tảng reuse-detection (`3013`) |
| **Check thiết bị** | **Có** — so `deviceId` claim với `session.deviceId` (`3016` nếu lệch) | **Không** — `refreshToken()` không kiểm `deviceId` |
| **Khi thiếu token / thiếu cookie** | **Bỏ qua filter** (không lỗi ngay, để `authorizeHttpRequests` tự chặn ở tầng sau → 401 không `code`) | **Ném lỗi ngay** (`3009 UNAUTHORIZED`, có `code`, kèm xóa cookie) |
| **Tác dụng phụ khi hợp lệ** | Cập nhật `lastSeen` | Cập nhật `lastSeen` **+** rotate `refreshJtiCurrent` (cấp AT/RT mới) |
| **Vòng lặp bao nhiêu request** | Chạy lại **mỗi request** có Bearer token | Chỉ chạy khi FE **chủ động** gọi (thường là khi AT hết hạn) |

> **Vì sao AT không cần reuse-detection?** Vì AT không rotate theo cơ chế "1 bản hiện hành duy
> nhất" như RT — nhiều AT hợp lệ (được cấp ở các lần login/refresh khác nhau, miễn cùng
> `sessionId` còn `ACTIVE`) có thể cùng tồn tại và cùng dùng được, miễn còn hạn và chưa bị
> blacklist. Điều khiến AT "chết" là: hết hạn tự nhiên, bị blacklist (logout), hoặc **session mà
> nó trỏ tới chết** — không phải vì có một AT khác "mới hơn" thay thế nó.

> **Vì sao filter check `deviceId` còn refresh thì không?** Đây là điểm bất đối xứng đáng chú ý
> trong code hiện tại — `JwtAuthFilter` so `deviceId` claim với session (`3016` nếu lệch), nhưng
> `refreshToken()` **không** làm việc này dù RT cũng mang claim `deviceId`. Về lý thuyết, RT bị
> đánh cắp và refresh từ thiết bị khác vẫn có thể qua được guard chain của `/refresh-token` nếu
> `jti` vẫn khớp `refreshJtiCurrent` (tức là chưa bị rotate bởi ai khác) — chỉ AT mới cấp ra từ đó
> mới bị chặn bởi filter khi request tiếp theo tới do device mismatch. Đây là điểm nên cân nhắc
> bổ sung nếu muốn khóa chặt hơn theo thiết bị ngay tại tầng refresh.

---

## 6. Response

**AT hợp lệ:** không có response riêng — filter chỉ set `Authentication` rồi `filterChain.doFilter(...)`, response do controller phía sau quyết định.

**AT lỗi (qua `CustomAuthException`):**

```jsonc
{ "status": 401, "code": 3016, "message": "Session device mismatch", "timestamp": "..." }
```

**Không gửi token / token bị bỏ qua ở bước đầu, bị chặn bởi `authorizeHttpRequests`:**

```jsonc
{ "status": 401, "message": "Full authentication is required to access this resource", "timestamp": "..." }
```

(không có `code` — xem §4, §7).

---

## 7. Bảng tra mã

### ErrorCode ném qua `CustomAuthException` (đều xử lý bởi `JwtAuthEntryPoint`, có `code`)

| Code | Tên | HTTP | Khi nào |
|:--:|---|:--:|---|
| 3015 | ACCESS_TOKEN_EXPIRED | 401 | AT hết hạn (`ExpiredJwtException`) — tín hiệu để FE gọi `/refresh-token` |
| 3001 | UNAUTHENTICATED | 401 | AT sai chữ ký/malformed; hoặc `username` session ≠ `sub` token; hoặc `isTokenValid` sai |
| 2010 | TOKEN_REVOKED | 401 | `jti` nằm trong `blacklist:access:*` (đã logout) |
| 3012 | SESSION_INACTIVE | 401 | session rỗng (không tồn tại) hoặc `status != ACTIVE` |
| 3016 | SESSION_DEVICE_MISMATCH | 401 | `deviceId` claim ≠ `deviceId` trong session |
| 2007 | ACCOUNT_BANNED | 403 | `user.isDeleted() = true` (kiểm tra lại từ DB, không tin claim cũ trong token) |
| 2005 | USER_INACTIVE | 403 | `user.isActive() = false` |

### Không có `code` (đi qua nhánh fallback của `JwtAuthEntryPoint`)

| Tình huống | HTTP | Message |
|---|:--:|---|
| Không gửi header `Authorization`, hoặc sai tiền tố (không phải `Bearer `) | 401 | "Full authentication is required to access this resource" (Spring Security mặc định) |
| `loadByUsername` ném `UsernameNotFoundException` (user biến mất khỏi DB) | 401 | "User not found: {username}" |

### `AccessDeniedException` (khác luồng — thiếu quyền, không phải thiếu xác thực)

| HTTP | Xử lý bởi |
|:--:|---|
| 403 | `JwtAccessDeniedHandler` — format riêng, không phải `ErrorResponse` (xem §8) |

---

## 8. Ghi chú & điểm dễ nhầm

- **Thiếu token hoàn toàn KHÔNG bị `JwtAuthFilter` chặn** — filter chủ động
  `filterChain.doFilter(...)` rồi `return`, để request đi tiếp **không có Authentication**. Việc
  chặn thực sự xảy ra sau đó, ở `authorizeHttpRequests().anyRequest().authenticated()` của
  Spring Security — đây là lý do response trong trường hợp này **không có `code`** (không đi qua
  `CustomAuthException`).
- **Guard `deviceId` (bước 6) chạy TRƯỚC khi load lại user từ DB (bước 7)** — nghĩa là một AT bị
  dùng sai thiết bị sẽ bị chặn (`3016`) ngay cả khi tài khoản đó vẫn hoàn toàn bình thường (chưa
  ban, chưa deactivate). Thứ tự này ưu tiên phát hiện bất thường về thiết bị trước khi tốn 1 query
  DB.
- **User luôn được tải lại từ DB mỗi request** (`loadByUsername`, có `@Transactional(readOnly =
  true)`, JOIN roles) — **không tin vào `roles` claim cũ trong token** cho việc kiểm
  `isDeleted`/`isActive` (vì các cờ này có thể đổi sau khi token đã phát hành). Tuy vậy,
  `Authorities` gắn vào `Authentication` (dùng cho `@PreAuthorize`) lấy từ **DB tươi** này, không
  phải từ claim `roles` trong JWT — nghĩa là **đổi role của user có hiệu lực ngay từ request tiếp
  theo**, không cần đợi AT hết hạn hay refresh.
- **`JwtAccessDeniedHandler` trả format khác hẳn `ErrorResponse`** (không có `code`, field tên
  khác: `error` thay vì cấu trúc chuẩn) — bất nhất với toàn bộ phần còn lại của hệ thống lỗi. Nếu
  FE có logic chung xử lý lỗi theo `code`, nhánh 403-thiếu-quyền sẽ không parse được `code` (luôn
  `undefined`) — cần lưu ý khi có tính năng dùng `@PreAuthorize`.
- **Guard chain của filter chạy lại từ đầu ở MỌI request có Bearer token**, kể cả khi
  `SecurityContextHolder` đã có sẵn `Authentication` (từ request khác trong cùng luồng xử lý, dù
  hiếm gặp) — chỉ có bước 7 (load user + set Authentication) là được bọc trong điều kiện
  "context chưa có Authentication"; các bước 1–6 (parse, blacklist, session, username, deviceId)
  luôn chạy đầy đủ.
- **Không có khái niệm "được whitelist do đã xác thực trước đó trong cùng session HTTP"** — vì
  `SessionCreationPolicy.STATELESS`, filter này **luôn** tự xác thực lại từ đầu cho mỗi request,
  không cache theo phiên trình duyệt.

---

*Đây là tài liệu cuối cùng của nhóm Auth cốt lõi. Xâu chuỗi lại: `1. Register & OTP.md` →
`2. Login.md` → `3. Kiến trúc Session & Token.md` → `4. Refresh token.md` → `5. Logout.md` →
`6. JwtAuthFilter.md` (bộ lọc áp dụng cho mọi request protected khác trong hệ thống, không riêng
gì nhóm Auth).*
