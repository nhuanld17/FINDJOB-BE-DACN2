# Luồng Access Token & Refresh Token

Tài liệu kỹ thuật mô tả **vòng đời đầy đủ** của access token (AT) và refresh token (RT) trong
module auth: cấp phát, xác thực, làm mới (rotation), phát hiện tái sử dụng (reuse detection), và
thu hồi. Đây là tài liệu tổng hợp — liên kết các thành phần đã được đặc tả chi tiết ở doc 2–6
thành một mô tả luồng liên tục.

- **Nguồn tham chiếu:** `AuthServiceImplement` (`login` / `refreshToken` / `logout`),
  `JwtAuthFilter`, `JwtUtil`, `RedisService`, `TokenBlacklistServiceImpl`, `.env`.
- **Cập nhật:** 2026-07-06.
- **Yêu cầu nền:** nên đọc trước `3. Kiến trúc Session & Token.md` (mô hình session, claim JWT,
  triết lý "session là nguồn sự thật").

## Mục lục
1. [Phạm vi & thành phần tham gia](#1-phạm-vi--thành-phần-tham-gia)
2. [Đặc tả token](#2-đặc-tả-token)
3. [Cấu trúc dữ liệu phía server](#3-cấu-trúc-dữ-liệu-phía-server)
4. [Vòng đời token theo giai đoạn](#4-vòng-đời-token-theo-giai-đoạn)
5. [Sơ đồ tuần tự tổng hợp](#5-sơ-đồ-tuần-tự-tổng-hợp)
6. [Máy trạng thái session](#6-máy-trạng-thái-session)
7. [Bảng mã trả về](#7-bảng-mã-trả-về)
8. [Cân nhắc bảo mật & giới hạn hiện tại](#8-cân-nhắc-bảo-mật--giới-hạn-hiện-tại)
9. [Tham chiếu chéo](#9-tham-chiếu-chéo)

---

## 1. Phạm vi & thành phần tham gia

Luồng AT/RT trải trên 4 endpoint và 1 bộ lọc. Bảng dưới liệt kê thành phần và vai trò của từng
thành phần trong vòng đời token:

| Thành phần | Loại | Vai trò trong luồng AT/RT |
|---|---|---|
| `AuthServiceImplement.login` | service | Cấp phát AT + RT lần đầu, tạo session |
| `JwtAuthFilter` | filter | Xác thực AT ở mỗi request tới endpoint protected |
| `AuthServiceImplement.refreshToken` | service | Đối chiếu RT, cấp AT + RT mới (rotation), phát hiện reuse |
| `AuthServiceImplement.logout` | service | Thu hồi session + blacklist AT/RT hiện hành |
| `JwtUtil` | util | Sinh (`buildToken`), phân tích (`extract*`), kiểm tra (`isTokenValid`, `remainingTimeOf`) token |
| `RedisService` | infra | Quản lý `session:{sessionId}`, `user:sessions:{userId}` |
| `TokenBlacklistServiceImpl` | service | Quản lý `blacklist:access:{jti}`, `blacklist:refresh:{jti}` |

Endpoint `/login`, `/refresh-token`, `/logout` nằm trong `PUBLIC_PATTERNS` (`/api/v1/auth/**`)
nên **không** đi qua `JwtAuthFilter`; chúng tự thực hiện xác thực token của riêng mình.

---

## 2. Đặc tả token

### 2.1 Claim (chung cho cả AT và RT)

Cả hai token được sinh bởi cùng một hàm `JwtUtil.buildToken(...)`, ký bằng **HMAC-SHA**
(`Keys.hmacShaKeyFor`, secret từ `jwt.secret`). Payload:

| Claim | Kiểu | Nguồn | Mục đích |
|---|---|---|---|
| `jti` | UUID | `UUID.randomUUID()` | Định danh duy nhất của token; dùng cho blacklist và so khớp `currentRefreshJti` |
| `sub` | String | `userDetails.getUsername()` | Username (không phải email); dùng để tra user |
| `roles` | String[] | `userDetails.getAuthorities()` | Danh sách quyền |
| `sessionId` | UUID | tham số lúc build | Trỏ tới `session:{sessionId}` trong Redis |
| `deviceId` | UUID | tham số lúc build (client cung cấp lúc login) | Ràng buộc token với thiết bị |
| `iat` | epoch | `Instant.now()` | Thời điểm phát hành |
| `exp` | epoch | `iat + expirationMs` | Thời điểm hết hạn |

AT và RT của cùng một lần cấp phát mang **giá trị giống nhau ở mọi claim trừ `jti` và `exp`**.

### 2.2 So sánh AT và RT

| Thuộc tính | Access Token | Refresh Token |
|---|---|---|
| Thời gian sống | **15 phút** (900 000 ms) | **7 ngày** (604 800 000 ms) |
| Vị trí lưu | Body response (FE tự giữ) | Cookie `HttpOnly` `Secure` `SameSite=Strict` `path=/` |
| Kênh truyền | Header `Authorization: Bearer <AT>` | Cookie tự động (chỉ tới `/refresh-token`, `/logout`) |
| Thành phần xác thực | `JwtAuthFilter` | `refreshToken()` / `logout()` |
| Rotation | Không | Có — mỗi lần refresh sinh RT mới |
| Cơ chế thu hồi | `blacklist:access:{jti}`; hoặc session không còn hợp lệ | `blacklist:refresh:{jti}`; hoặc `jti ≠ currentRefreshJti`; hoặc session không còn hợp lệ |

### 2.3 Cấu hình vòng đời

Giá trị lấy từ biến môi trường (`.env`), inject vào `JwtUtil` qua `application.yml`:

```yaml
# application.yml
jwt:
  secret: ${JWT_SECRET}
  access-token-expiration-ms:  ${ACCESS_TOKEN_LIFETIME}   # .env: 900000     (15 phút)
  refresh-token-expiration-ms: ${REFRESH_TOKEN_LIFETIME}  # .env: 604800000  (7 ngày)
```

Cookie `refreshToken` đặt `maxAge = 7 * 24 * 60 * 60` giây (đồng bộ với `REFRESH_TOKEN_LIFETIME`)
tại cả `login` và `refreshToken`.

### 2.4 Nguyên tắc thiết kế (design rationale)

Mô hình 2 token cân bằng giữa rủi ro lộ token và trải nghiệm người dùng:

- **AT sống ngắn** vì nó xuất hiện trong *mọi* request (bề mặt lộ rộng); thời gian sống ngắn giới
  hạn cửa sổ khai thác nếu bị lộ. AT là JWT thuần, không có bản ghi Redis riêng.
- **RT sống dài** để không phải đăng nhập lại thường xuyên, nhưng chỉ truyền tới 2 endpoint và
  nằm trong cookie `HttpOnly` (JS không đọc được) → bề mặt lộ hẹp; đồng thời RT **có thể bị thu
  hồi** thông qua đối chiếu `currentRefreshJti` và blacklist.

---

## 3. Cấu trúc dữ liệu phía server

Luồng AT/RT thao tác trên các key Redis sau (chi tiết `3. Kiến trúc Session & Token.md` §3):

| Key | Kiểu | TTL | Vai trò |
|---|---|---|---|
| `session:{sessionId}` | Hash | 7 ngày (fixed-window, đặt 1 lần lúc login) | Nguồn sự thật về phiên; chứa `currentRefreshJti`, `status`, ... |
| `user:sessions:{userId}` | Set | không có | Danh sách sessionId của user |
| `blacklist:access:{jti}` | String | = thời gian còn lại của AT | Đánh dấu AT bị thu hồi |
| `blacklist:refresh:{jti}` | String | = thời gian còn lại của RT | Đánh dấu RT bị thu hồi |

Field then chốt cho luồng RT là **`session:{sessionId}.currentRefreshJti`** — lưu `jti` của RT
đang được coi là hợp lệ. Đây là "con trỏ" xác định bản RT hiện hành; mọi so khớp reuse-detection
dựa vào field này.

---

## 4. Vòng đời token theo giai đoạn

Vòng đời chia thành 5 giai đoạn: cấp phát → xác thực → làm mới → (nhánh) phát hiện reuse → thu hồi.

### 4.1 Giai đoạn 1 — Cấp phát (Login)

Điều kiện: xác thực email/password thành công và tài khoản `ACTIVE` (chi tiết `2. Login.md`).
Trình tự trong `login()`:

```
1. sessionId ← UUID.randomUUID()
2. accessToken  ← jwtUtil.generateAccessToken(userDetails, sessionId, deviceId)   // exp +15 phút
   refreshToken ← jwtUtil.generateRefreshToken(userDetails, sessionId, deviceId)  // exp +7 ngày
3. sessionService.createSession(sessionId, username, deviceId, jti(refreshToken), deviceName, ip, userAgent)
      → session:{sessionId} = { status: ACTIVE, currentRefreshJti: jti(refreshToken), ... }
      → TTL(session) = 7 ngày
4. sessionService.addSessionToUser(userId, sessionId)
5. Set-Cookie: refreshToken=<refreshToken>   (HttpOnly, Secure, SameSite=Strict, maxAge 7 ngày)
6. return AuthResponse { code: 4001, id, username, roles, accessToken }   // AT trong body
```

Kết quả: client giữ AT (body) + RT (cookie); server có `session:{sessionId}` với
`currentRefreshJti = jti(RT₀)`.

### 4.2 Giai đoạn 2 — Xác thực request (JwtAuthFilter)

Mỗi request tới endpoint protected mang `Authorization: Bearer <AT>`. `JwtAuthFilter` thực thi
chuỗi guard (chi tiết `6. JwtAuthFilter.md` §3):

| Bước | Kiểm tra | Kết quả nếu thất bại |
|:--:|---|---|
| 1 | Parse claim; token hết hạn (`ExpiredJwtException`) | `3015 ACCESS_TOKEN_EXPIRED` |
| 1 | Parse claim; token malformed/sai chữ ký | `3001 UNAUTHENTICATED` |
| 2 | `jti ∈ blacklist:access:*` | `2010 TOKEN_REVOKED` |
| 3 | `session:{sessionId}` tồn tại (HGETALL) | `3012 SESSION_INACTIVE` |
| 4 | `session.status == ACTIVE` | `3012 SESSION_INACTIVE` |
| 5 | `session.username == sub` | `3001 UNAUTHENTICATED` |
| 6 | `session.deviceId == deviceId (claim)` | `3016 SESSION_DEVICE_MISMATCH` |
| 7 | User (DB tươi) `isDeleted` / `!isActive` | `2007 ACCOUNT_BANNED` / `2005 USER_INACTIVE` |
| 7 | `isTokenValid(token, userDetails)` | `3001 UNAUTHENTICATED` |

Khi qua hết: filter set `Authentication` vào `SecurityContextHolder`, cập nhật
`session.lastSeen`, và chuyển request tới controller. AT được tái sử dụng cho mọi request trong
suốt 15 phút; RT không tham gia giai đoạn này.

**Đặc điểm:** chữ ký hợp lệ và còn hạn chỉ vượt qua bước 1. AT còn phải khớp với
`session:{sessionId}` (bước 3–6) và user hiện tại trong DB (bước 7). Do đó session bị thu hồi làm
AT mất hiệu lực ngay, không cần chờ `exp`.

### 4.3 Giai đoạn 3 — Làm mới (Refresh + Rotation)

Khi AT hết hạn, `JwtAuthFilter` trả `3015`. FE dùng tín hiệu này để gọi
`POST /api/v1/auth/refresh-token` (cookie RT tự động gửi kèm). `refreshToken()` chạy guard chain
(chi tiết `4. Refresh token.md` §3):

| Bước | Kiểm tra | Kết quả nếu thất bại |
|:--:|---|---|
| 1 | Cookie rỗng / parse claim lỗi | `3009 UNAUTHORIZED` (xóa cookie) |
| 2 | `jti ∈ blacklist:refresh:*` | `2010 TOKEN_REVOKED` |
| 3 | `session` tồn tại / `status == ACTIVE` / `currentRefreshJti` khác null | `3012 SESSION_INACTIVE` (xóa cookie) |
| 4 | `jti == session.currentRefreshJti` | **KHÔNG khớp → nhánh reuse §4.4** |
| 5 | User `isDeleted` / `!isActive` | `2007` / `2005` (xóa cookie) |
| 6 | `isTokenValid` | `3009 UNAUTHORIZED` (xóa cookie) |

Khi khớp và user hợp lệ, thực hiện **rotation**:

```
a. accessToken_new  ← generateAccessToken(userDetails, sessionId, deviceId)   // exp +15 phút
b. refreshToken_new ← generateRefreshToken(userDetails, sessionId, deviceId)  // exp +7 ngày
c. session.currentRefreshJti ← jti(refreshToken_new)   // con trỏ chuyển sang RT mới
d. session.lastSeen ← now
e. Set-Cookie: refreshToken=<refreshToken_new>
f. return AuthResponse { code: 4001, accessToken: accessToken_new, ... }
```

Sau bước (c), RT cũ có `jti ≠ currentRefreshJti` → mất hiệu lực. RT cũ **không được đưa vào
blacklist** (xem §4.4 để biết lý do). `sessionId` **không** đổi; TTL của `session:{sessionId}`
**không** được gia hạn (vẫn hết hạn theo mốc 7 ngày kể từ login gốc — fixed-window, xem
`3. Kiến trúc Session & Token.md` §6).

### 4.4 Giai đoạn 4 — Phát hiện tái sử dụng (Reuse Detection)

Xảy ra ở bước 4 của §4.3 khi `jti(RT) ≠ currentRefreshJti`, tức RT đã bị thay thế bởi một lần
rotation trước đó nhưng vẫn được gửi lại. Đây được coi là dấu hiệu RT bị lộ/replay. Xử lý:

```
session.status ← REVOKED
tokenBlacklistService.revokeRefreshToken(jti, remainingTimeOf(RT))   // blacklist RT bị replay
throw AppException(TOKEN_REUSE_DETECTED)   // 3013
```

Hệ quả: session chuyển `REVOKED` → toàn bộ token thuộc phiên (kể cả RT/AT hiện hành) mất hiệu lực
ở các lần kiểm tra sau. Đây là mô hình **refresh token family**: một RT đã bị thay thế được dùng
lại sẽ vô hiệu hóa cả phiên, buộc đăng nhập lại.

**Lý do RT cũ không bị blacklist tại thời điểm rotation ("Hướng B"):** guard blacklist (bước 2,
`2010`) chạy trước guard so khớp `currentRefreshJti` (bước 4, `3013`). Nếu rotation blacklist RT
cũ, thì mọi lần replay RT cũ sẽ dừng ở bước 2 với `2010` và **không bao giờ tới bước 4** — reuse
detection không được kích hoạt, `session.status` vẫn `ACTIVE`. Để reuse detection hoạt động, RT
cũ phải "chết" bằng cơ chế lệch `currentRefreshJti` (bước 4), không phải bằng blacklist. Phân
tích đầy đủ: `4. Refresh token.md` §5.

Tóm tắt phân vai: `2010` dành cho thu hồi chủ động (logout); `3013` dành cho phát hiện replay.
Hai cơ chế độc lập, không ghi đè lên nhau.

### 4.5 Giai đoạn 5 — Thu hồi & kết thúc

Một phiên (và cặp token) kết thúc theo 3 cơ chế:

| Cơ chế | Tác nhân | `session:{sessionId}` | Blacklist |
|---|---|---|---|
| **Logout** | Người dùng | Xóa hẳn (`deleteSession`) | AT + RT hiện hành |
| **Reuse detected** | Hệ thống (§4.4) | `status = REVOKED` (giữ hash) | RT bị replay |
| **Hết TTL** | Redis (7 ngày) | Redis tự xóa | Không |

Trình tự `logout()` (chi tiết `5. Logout.md`):

```
1. Luôn xóa cookie refreshToken (idempotent)
2. Nếu không có RT / parse lỗi / session không tồn tại → return (HTTP 200)
3. deleteSession(sessionId)
4. removeSessionFromUser(userId, sessionId)   // best-effort
5. Nếu có AT (header) và remaining > 0 → blacklist AT
6. Nếu remaining(RT) > 0 → blacklist currentRefreshJti
7. return 200 { code: 1000 }
```

Sau khi thu hồi: AT cũ dùng lại → `2010` (nếu đã blacklist) hoặc `3012` (session đã xóa); RT cũ
đem refresh → `2010` / `3012`.

---

## 5. Sơ đồ tuần tự tổng hợp

Một chu kỳ đầy đủ: login → dùng AT → AT hết hạn → refresh → dùng AT mới.

```
Client                        JwtAuthFilter        AuthService              Redis
  │                                                                          │
  │ POST /login (email,pwd,deviceId,deviceName)                              │
  │ ───────────────────────────────────────────►  login()                   │
  │                                                createSession ───────────►│ session ACTIVE
  │ ◄─────────────────────────────────────────── 200 {code:4001, AT₁}       │ currentRefreshJti=jti(RT₁)
  │  Set-Cookie: RT₁                                                          │
  │                                                                          │
  │ GET /resource  Authorization: Bearer AT₁                                 │
  │ ─────────────────────────►  guard chain ──────────────────────────────►│ đọc session, lastSeen
  │ ◄─────────────────────────  200 (resource)                              │
  │                                                                          │
  │            ... (15 phút trôi qua, AT₁ hết hạn) ...                       │
  │                                                                          │
  │ GET /resource  Authorization: Bearer AT₁                                 │
  │ ─────────────────────────►  guard bước 1: hết hạn                       │
  │ ◄─────────────────────────  401 {code:3015}                             │
  │                                                                          │
  │ POST /refresh-token   Cookie: RT₁                                        │
  │ ───────────────────────────────────────────►  refreshToken()            │
  │                                                bước 4: jti(RT₁)==current?│ ✓ khớp
  │                                                rotation ────────────────►│ currentRefreshJti=jti(RT₂)
  │ ◄─────────────────────────────────────────── 200 {code:4001, AT₂}       │
  │  Set-Cookie: RT₂                                                          │
  │                                                                          │
  │ GET /resource  Authorization: Bearer AT₂  → 200                          │
  │                                                                          │
  │ POST /logout   Cookie: RT₂  [Authorization: Bearer AT₂]                  │
  │ ───────────────────────────────────────────►  logout()                  │
  │                                                deleteSession ───────────►│ session xóa
  │                                                blacklist AT₂, RT₂ ──────►│ blacklist:*
  │ ◄─────────────────────────────────────────── 200 {code:1000}            │
  │  Set-Cookie: refreshToken=; Max-Age=0                                     │
```

---

## 6. Máy trạng thái session

`session:{sessionId}` là thực thể quyết định hiệu lực của mọi token thuộc phiên. Các chuyển trạng thái:

```
                 login
                   │  createSession
                   ▼
             ┌───────────┐   refresh (jti khớp)
             │  ACTIVE   │─────────────────────────┐  cập nhật currentRefreshJti
             │           │◄─────────────────────────┘  (vẫn ACTIVE)
             └─────┬─────┘
        ┌──────────┼──────────────┬────────────────────┐
        │          │              │                    │
   refresh       logout        hết TTL           refresh (jti KHÔNG khớp)
  (jti khác)       │           (7 ngày)                 │
        │          │              │                    │
        ▼          ▼              ▼                    ▼
   ┌─────────┐  (key bị      (key bị Redis        ┌─────────┐
   │ REVOKED │   xóa hẳn)     tự xóa)             │ REVOKED │
   └─────────┘                                    └─────────┘
```

| Trạng thái | Ý nghĩa | AT thuộc phiên | RT thuộc phiên |
|---|---|---|---|
| `ACTIVE` | Phiên hợp lệ | Dùng được nếu còn hạn + không blacklist | RT hiện hành (khớp `currentRefreshJti`) dùng được |
| `REVOKED` | Bị thu hồi do reuse | Bị chặn (`3012`) | Bị chặn (`3012` / `2010`) |
| *(không tồn tại)* | Đã logout hoặc hết TTL | Bị chặn (`2010` / `3012`) | Bị chặn (`2010` / `3012`) |

---

## 7. Bảng mã trả về

### Access token (qua `JwtAuthFilter`)

| Mã | Tên | HTTP | Điều kiện |
|:--:|---|:--:|---|
| 3015 | ACCESS_TOKEN_EXPIRED | 401 | AT hết hạn — tín hiệu để FE gọi refresh |
| 3001 | UNAUTHENTICATED | 401 | AT sai chữ ký; username ≠ sub; `isTokenValid` sai |
| 2010 | TOKEN_REVOKED | 401 | `jti ∈ blacklist:access:*` |
| 3012 | SESSION_INACTIVE | 401 | Session không tồn tại / không `ACTIVE` |
| 3016 | SESSION_DEVICE_MISMATCH | 401 | `deviceId` claim ≠ `session.deviceId` |
| 2007 / 2005 | ACCOUNT_BANNED / USER_INACTIVE | 403 | User bị ban / chưa active (DB tươi) |

### Refresh token (qua `refreshToken()`)

| Mã | Tên | HTTP | Điều kiện |
|:--:|---|:--:|---|
| 4001 | LOGIN_SUCCESS | 200 | Refresh thành công (tái dùng mã của login) |
| 3009 | UNAUTHORIZED | 401 | Cookie rỗng / parse lỗi / `isTokenValid` sai |
| 2010 | TOKEN_REVOKED | 401 | `jti ∈ blacklist:refresh:*` (thường sau logout) |
| 3012 | SESSION_INACTIVE | 401 | Session không tồn tại / không `ACTIVE` / thiếu `currentRefreshJti` |
| 3013 | TOKEN_REUSE_DETECTED | 401 | `jti ≠ currentRefreshJti` (replay RT đã bị thay thế) |
| 2007 / 2005 | ACCOUNT_BANNED / USER_INACTIVE | 403 | User bị ban / chưa active |
| 2001 | USER_NOT_FOUND | 404 | User biến mất khỏi DB |

`refreshToken()` **không** có mã success riêng — dùng chung `4001` với login.

---

## 8. Cân nhắc bảo mật & giới hạn hiện tại

| Chủ đề | Mô tả |
|---|---|
| **Session TTL fixed-window** | Session hết hạn đúng 7 ngày kể từ login gốc, không gia hạn khi refresh. RT mới có `exp` xa hơn nhưng vô nghĩa một khi session bị xóa. Đây là "trần cứng" tuổi thọ phiên — quyết định thiết kế, không phải bug. |
| **AT cũ không bị vô hiệu khi refresh** | Sau rotation, AT cũ vẫn dùng được đến khi hết hạn tự nhiên (phần còn lại của 15 phút) hoặc session chết. Refresh không nhận AT nên không thể blacklist nó. |
| **Refresh không kiểm `deviceId`** | `JwtAuthFilter` kiểm `deviceId` (`3016`) nhưng `refreshToken()` thì không. RT bị đánh cắp có thể refresh từ thiết bị khác nếu `jti` còn khớp `currentRefreshJti`; chỉ AT phát sinh sau đó mới bị filter chặn khi request tiếp theo. Điểm bất đối xứng nên cân nhắc siết. |
| **Reuse detection "chặt"** | Client refresh nhưng mất response rồi retry bằng RT cũ sẽ bị coi là replay → `REVOKED` oan. Chưa có grace-window chấp nhận `jti` liền trước. |
| **Nhánh `2010`/`2001` không xóa cookie** | Trong `refreshToken()`, hai nhánh này không gọi `clearRefreshTokenCookie` (khác 3009/3012/3013). Không phải lỗ hổng nhưng thiếu nhất quán. |
| **`user:sessions:{userId}` tích rác** | Session hết TTL không tự gỡ `sessionId` khỏi set này (chỉ logout mới gỡ). Cần lọc bằng `hasKey` nếu xây tính năng liệt kê thiết bị. |

---

## 9. Tham chiếu chéo

| Chủ đề | Tài liệu |
|---|---|
| Cấp phát token (login), luồng active/inactive | `2. Login.md` |
| Mô hình session, claim JWT, Redis key, triết lý nguồn sự thật | `3. Kiến trúc Session & Token.md` |
| Refresh + rotation + reuse detection (chi tiết) | `4. Refresh token.md` |
| Logout + blacklist + idempotency | `5. Logout.md` |
| Xác thực AT ở mỗi request | `6. JwtAuthFilter.md` |
