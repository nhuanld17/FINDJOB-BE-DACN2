# Kiến trúc Session & Token

> Tài liệu **nền tảng**, không mô tả một endpoint cụ thể mà mô tả **mô hình chung** đứng sau
> Login / Refresh token / Logout / `JwtAuthFilter`: JWT mang claim gì, Redis lưu gì, ai là
> "nguồn sự thật" khi JWT và Redis lệch nhau.
>
> Bám sát code: `JwtUtil` + `SessionService` (nhóm `session:*` / `user:sessions:*`, prefix private trong `SessionServiceImpl`) +
> `TokenBlacklistServiceImpl` (nhóm `blacklist:*`) + `JwtAuthFilter` + `AuthServiceImplement`
> (`login` / `refreshToken` / `logout`). Cập nhật: 2026-07-05.
>
> 📎 Doc `2. Login.md` đã nhắc TTL 7 ngày fixed-window; doc `4. Refresh token.md`,
> `5. Logout.md`, `6. JwtAuthFilter.md` sẽ **link về đây** thay vì lặp lại giải thích.

## Mục lục
1. [Tổng quan mô hình](#1-tổng-quan-mô-hình)
2. [JWT — cấu trúc & claim](#2-jwt--cấu-trúc--claim)
3. [Redis — 2 nhóm key](#3-redis--2-nhóm-key)
4. [Vòng đời một session](#4-vòng-đời-một-session)
5. [Triết lý "session là nguồn sự thật"](#5-triết-lý-session-là-nguồn-sự-thật)
6. [TTL 7 ngày & fixed-window](#6-ttl-7-ngày--fixed-window)
7. [Bảng tra cứu nhanh](#7-bảng-tra-cứu-nhanh)
8. [Ghi chú & điểm dễ nhầm](#8-ghi-chú--điểm-dễ-nhầm)

---

## 1. Tổng quan mô hình

Hệ thống dùng **JWT lai session** (hybrid), không phải JWT thuần stateless:

- **JWT** (access + refresh) tự chứa đủ thông tin để **verify chữ ký + đọc claim** mà không cần
  query DB — đúng tinh thần stateless.
- Nhưng **mọi request có token** (qua `JwtAuthFilter`) và **mọi lần refresh** (qua
  `refreshToken()`) đều phải đối chiếu thêm với **session lưu trong Redis**. Token hợp lệ về mặt
  chữ ký/`exp` **chưa chắc còn dùng được** — session mới là cái quyết định cuối cùng.

```
                     ┌────────────────────────┐
   accessToken  ────►│   JwtAuthFilter         │
   (header)          │   verify chữ ký + exp   │──► còn phải khớp với session:{sessionId}
                     └────────────────────────┘     (status=ACTIVE, username, deviceId)

                     ┌────────────────────────┐
   refreshToken ────►│   refreshToken()        │──► còn phải khớp currentRefreshJti
   (cookie)          │   verify chữ ký + exp   │     trong session:{sessionId}
                     └────────────────────────┘
```

Vì sao không thuần stateless? Vì JWT thuần **không thể bị thu hồi trước hạn** (logout, phát hiện
lộ token, ban tài khoản...). Redis session là "công tắc ngắt" (kill switch) cho việc đó.

---

## 2. JWT — cấu trúc & claim

Cả `accessToken` và `refreshToken` đi qua **cùng một hàm** `JwtUtil.buildToken(...)`, chỉ khác
nhau ở `expirationMs` truyền vào (`jwt.access-token-expiration-ms` vs
`jwt.refresh-token-expiration-ms`, cấu hình qua env `ACCESS_TOKEN_LIFETIME` /
`REFRESH_TOKEN_LIFETIME`) và dĩ nhiên là `jti` khác nhau mỗi lần sinh.

| Claim | Nguồn | Ý nghĩa |
|---|---|---|
| `jti` (id) | `UUID.randomUUID()` | định danh **duy nhất của chính token này** — dùng để blacklist & để so khớp "token nào đang là bản hiện hành" |
| `sub` (subject) | `userDetails.getUsername()` | **username**, không phải email (xem lưu ý ở `2. Login.md` §6) |
| `roles` | `userDetails.getAuthorities()` | danh sách quyền, ví dụ `["ROLE_USER"]` |
| `sessionId` | truyền vào khi build | UUID đại diện **1 phiên đăng nhập trên 1 thiết bị** — access & refresh cùng phiên mang chung `sessionId` |
| `deviceId` | truyền vào khi build (client gửi lúc login) | định danh thiết bị, dùng để `JwtAuthFilter` phát hiện token bị mang sang thiết bị khác |
| `iat` | `now` | thời điểm phát hành |
| `exp` | `now + expirationMs` | access sống ngắn, refresh sống 7 ngày |

Ký bằng HMAC-SHA (`Keys.hmacShaKeyFor`) với secret từ `jwt.secret` (env `JWT_SECRET`).

> **`accessToken` và `refreshToken` của cùng 1 lần login/refresh luôn mang chung `sessionId` và
> `deviceId`**, chỉ khác `jti` và `exp`. Đây là "sợi dây" nối 2 token với đúng 1 bản ghi
> `session:{sessionId}` trong Redis.

---

## 3. Redis — 2 nhóm key

### Nhóm 1 — Session (nguồn sự thật về phiên đăng nhập)

**`session:{sessionId}`** — kiểu **Hash**, tạo bởi `SessionService.createSession(...)` lúc login:

| Field | Giá trị | Cập nhật khi nào |
|---|---|---|
| `username` | username user | chỉ ghi lúc tạo |
| `deviceId` | deviceId lúc login | chỉ ghi lúc tạo |
| `currentRefreshJti` | `jti` của **refresh token đang hợp lệ** | ghi lúc tạo; **ghi đè** mỗi lần refresh thành công (rotation) |
| `status` | `ACTIVE` \| `REVOKED` | ghi `ACTIVE` lúc tạo; chuyển `REVOKED` khi phát hiện reuse |
| `createdAt` | timestamp lúc tạo | chỉ ghi lúc tạo |
| `lastSeen` | timestamp | cập nhật mỗi request hợp lệ qua `JwtAuthFilter`, và mỗi lần refresh |
| `deviceName` / `ip` / `userAgent` | metadata từ request lúc login | chỉ ghi lúc tạo |

TTL: **7 ngày, đặt 1 lần lúc tạo, không bao giờ gia hạn** (xem §6).

**`user:sessions:{userId}`** — kiểu **Set**, chứa danh sách `sessionId` đang thuộc về 1 user
(một user login nhiều thiết bị = nhiều phần tử). Dùng để:
- Thêm khi login (`addSessionToUser`).
- Xóa 1 phần tử khi logout (`removeSessionFromUser`).
- (Hạ tầng sẵn có cho tính năng tương lai: "danh sách thiết bị đã đăng nhập", "đăng xuất tất cả
  thiết bị" — xem `getUserSessions` / `deleteAllUserSessionsIndex`, hiện **chưa có endpoint** gọi
  tới 2 hàm này).

Set này **không có TTL riêng** — sống đến khi bị xóa thủ công; nếu `session:{sessionId}` hết hạn
tự nhiên (7 ngày trôi qua mà không logout), `sessionId` vẫn còn "rác" trong set cho tới khi có
thao tác dọn (chưa có cron dọn — xem §8).

### Nhóm 2 — Blacklist (thu hồi trước hạn)

**`blacklist:refresh:{jti}`** và **`blacklist:access:{jti}`** — kiểu **String**, giá trị
`"revoked"`, TTL = **thời gian còn lại của chính token đó** (`jwtUtil.remainingTimeOf(token)`).

- TTL trùng khớp `exp` còn lại → key blacklist **tự biến mất đúng lúc token cũng hết hạn tự
  nhiên** — không cần dọn dẹp thủ công, không phình kho vô hạn.
- Được ghi khi: **logout** (blacklist cả AT hiện tại lẫn RT hiện tại), hoặc **phát hiện reuse**
  khi refresh (chỉ blacklist RT bị lộ).
- Được kiểm khi: `JwtAuthFilter` kiểm **access token** mỗi request; `refreshToken()` kiểm
  **refresh token** ở bước đầu tiên.

```
Redis
├── session:{sessionId}            Hash   TTL 7d (fixed, đặt 1 lần)
├── user:sessions:{userId}         Set    (không TTL — xem §8)
├── blacklist:refresh:{jti}        String TTL = remaining(refreshToken)
└── blacklist:access:{jti}         String TTL = remaining(accessToken)
```

> Nhóm `pending:*` (pending token cho luồng OTP) là một hệ key **khác**, không liên quan tới
> session/token đăng nhập — xem `1. Register & OTP.md` §2. (Prefix `pending:*` → `PendingTokenConstant`, `otp:*` → `OtpConstant` ở `common/constant/`.)

---

## 4. Vòng đời một session

```
login
  │  createSession() → session:{sessionId} status=ACTIVE, currentRefreshJti=jti(RT₀)
  │  addSessionToUser()
  ▼
mỗi request (accessToken)  ──► JwtAuthFilter: verify + đối chiếu session (status/username/deviceId)
  │                              → hợp lệ: cập nhật lastSeen, cho qua
  │                              → không hợp lệ: 401/403 tương ứng (không đổi session)
  ▼
refresh (refreshToken RTₙ)
  │  jti(RTₙ) == currentRefreshJti ?
  │     ├─ ĐÚNG  → cấp AT mới + RT mới (RTₙ₊₁), ghi đè currentRefreshJti = jti(RTₙ₊₁)
  │     │          (RTₙ KHÔNG bị blacklist ngay — xem §8 & doc Refresh token)
  │     └─ SAI    → status = REVOKED, blacklist jti(RTₙ), ném TOKEN_REUSE_DETECTED
  │                 → session coi như CHẾT, phải login lại
  ▼
logout
  │  deleteSession(sessionId)               ← xóa hẳn hash, không chỉ đổi status
  │  removeSessionFromUser(userId, sessionId)
  │  blacklist AT hiện tại (nếu có) + RT hiện tại
  ▼
hết hạn tự nhiên (7 ngày không logout, không bị revoke)
  │  Redis tự xóa session:{sessionId} theo TTL
  │  (user:sessions:{userId} vẫn còn sessionId "rác" — xem §8)
```

Có **3 cách một session "chết"**, khác nhau về cơ chế:

| Cách chết | `status` cuối | Key `session:{}` | Có blacklist token không |
|---|---|---|---|
| **Logout** | (bị xóa, không còn field) | bị **xóa hẳn** | có (AT + RT hiện tại) |
| **Reuse detected** | `REVOKED` (giữ lại) | vẫn còn, chỉ đổi field | có (chỉ RT bị lộ) |
| **Hết TTL tự nhiên** | (bị xóa theo TTL Redis) | bị Redis **tự xóa** | không (token cũng gần/đã hết hạn) |

---

## 5. Triết lý "session là nguồn sự thật"

> JWT trả lời được **"token này có hợp lệ về mặt mật mã không, ai ký, còn hạn không"**, nhưng
> **không** trả lời được **"phiên đăng nhập này còn được phép dùng không, ngay bây giờ"**.
> Câu hỏi thứ hai luôn được hỏi lại Redis.

Hệ quả trực tiếp của triết lý này:

- **Access token còn hạn (`exp` chưa qua) vẫn có thể bị từ chối ngay lập tức** nếu: session đã bị
  xóa/`REVOKED` (`3012`), `jti` nằm trong blacklist (`2010`), `deviceId`/`username` không khớp
  session (`3016`/`3001`), hoặc user vừa bị ban/deactivate (`2007`/`2005`). Đây chính là cách hệ
  thống "thu hồi" một JWT vốn dĩ stateless.
- **Refresh token đúng chữ ký, đúng hạn** vẫn bị từ chối nếu `jti` không khớp
  `currentRefreshJti` — vì tại thời điểm đó, session coi token khác mới là "bản hiện hành".
- Ngược lại, **session còn `ACTIVE`** không có nghĩa mọi token cũ đều dùng được — chỉ **token
  đang khớp claim hiện tại của session** mới qua được các bước đối chiếu.

Nói ngắn gọn: **JWT chứng minh danh tính + tính toàn vẹn; Redis session quyết định quyền được
sống**. Muốn "giết" một phiên bất kỳ lúc nào (logout, admin ban, phát hiện lộ token) → chỉ cần
sửa/xóa `session:{sessionId}`, không cần đụng tới bản thân JWT (vốn không thể sửa được sau khi
ký).

---

## 6. TTL 7 ngày & fixed-window

`SessionService.createSession(...)` set TTL cho `session:{sessionId}` = **7 ngày, đúng 1 lần lúc
tạo**. Các thao tác sau đó (`updateSessionField` khi refresh, khi cập nhật `lastSeen`) dùng
`HSET` — **không** đụng tới TTL của key. Nghĩa là:

- TTL session là **giới hạn tuyệt đối tính từ lúc login**, không phải "sliding window" (không
  giống kiểu "còn hoạt động thì còn được gia hạn").
- **Refresh token rotation cấp lại `refreshToken` mới với `exp` = now + 7 ngày**, nhưng session
  đứng sau nó vẫn hết hạn đúng vào mốc 7 ngày kể từ **login gốc** — không phải 7 ngày kể từ lần
  refresh gần nhất.
- ⇒ Hệ quả thực tế: **dù user hoạt động liên tục và refresh đều đặn, sau đúng 7 ngày kể từ lúc
  login, lần refresh tiếp theo sẽ thất bại** (`session:{sessionId}` đã bị Redis xóa →
  `SESSION_INACTIVE`), bắt buộc phải login lại. Đây là **quyết định thiết kế có chủ đích** (fixed
  absolute session lifetime), không phải bug.

Cookie `refreshToken` cũng đặt `maxAge = 7 * 24 * 60 * 60` giây ở cả `login` lẫn `refreshToken`,
đồng bộ với TTL session — nên trong điều kiện bình thường, cookie, JWT `exp`, và Redis TTL cùng
"hết hạn quanh một mốc", tránh tình trạng cookie còn mà session đã chết từ lâu (chỉ lệch khi có
revoke sớm).

---

## 7. Bảng tra cứu nhanh

| Key pattern | Kiểu | TTL | Ghi bởi | Đọc bởi |
|---|---|---|---|---|
| `session:{sessionId}` | Hash | 7 ngày, fixed (đặt 1 lần) | `login` (tạo), `refreshToken` (update field), reuse-detect (update field) | `JwtAuthFilter`, `refreshToken`, `logout` |
| `user:sessions:{userId}` | Set | không có (persistent) | `login` (add), `logout` (remove) | (chưa có endpoint đọc — hạ tầng sẵn) |
| `blacklist:access:{jti}` | String | = thời gian còn lại của AT | `logout` | `JwtAuthFilter` |
| `blacklist:refresh:{jti}` | String | = thời gian còn lại của RT | `logout`, reuse-detect | `refreshToken` |

| Claim JWT | Có ở access? | Có ở refresh? | Dùng để |
|---|:--:|:--:|---|
| `jti` | ✓ | ✓ | blacklist lookup; so khớp `currentRefreshJti` (chỉ RT) |
| `sub` (username) | ✓ | ✓ | tra user, so khớp `session.username` |
| `roles` | ✓ | ✓ | cấp quyền (`Authorities`) khi build `Authentication` |
| `sessionId` | ✓ | ✓ | trỏ tới `session:{sessionId}` |
| `deviceId` | ✓ | ✓ | so khớp `session.deviceId` (AT) — chống mang AT sang thiết bị khác |
| `exp` / `iat` | ✓ | ✓ | validate hạn dùng |

---

## 8. Ghi chú & điểm dễ nhầm

- **`revokeSession(sessionId)` trong `RedisService` hiện không được gọi ở đâu** — logout dùng
  `deleteSession` (xóa hẳn), reuse-detect dùng `updateSessionField("status", "REVOKED")` trực
  tiếp. Hàm `revokeSession` là tiện ích sẵn có nhưng chưa được dùng tới trong luồng hiện tại.
- **Reuse-detect KHÔNG blacklist ngay refresh token vừa rotate xong** (RTₙ sau khi cấp RTₙ₊₁).
  Lý do: cơ chế chống-reuse đã dựa vào so khớp `currentRefreshJti`, nên RT cũ tự động "chết" khi
  không còn khớp — nếu bị replay sẽ tự rơi vào nhánh reuse (giết cả session). Blacklist thêm ở
  đây sẽ khiến guard `TOKEN_REVOKED` (2010) chặn trước, không bao giờ tới được nhánh phát hiện
  reuse → mất khả năng "phát hiện lộ token ⇒ hủy cả family". Chi tiết & hệ quả bảo mật xem
  `4. Refresh token.md`.
- **`user:sessions:{userId}` có thể tích rác**: session hết hạn tự nhiên theo TTL Redis nhưng
  `sessionId` của nó không tự bị gỡ khỏi set này (chỉ `logout` mới gỡ). Hiện chưa có cơ chế dọn
  định kỳ — nếu sau này xây tính năng "liệt kê thiết bị đang đăng nhập", cần tự lọc thêm bằng
  cách kiểm tra `hasKey("session:" + id)` cho từng phần tử.
- **`accessToken` không có bản ghi Redis riêng** (không có `access:{jti}` như session) — nó
  thuần JWT, chỉ bị chặn khi: hết hạn tự nhiên, nằm trong `blacklist:access:*`, hoặc session mà
  nó trỏ tới không còn hợp lệ. Không có khái niệm "thu hồi 1 access token cụ thể" độc lập với
  logout/blacklist.
- **`refreshToken()` khi thành công vẫn trả `SuccessCode.LOGIN_SUCCESS` (4001)** — không có mã
  riêng cho "refresh thành công", tái dùng chung code với login. Cần lưu ý khi FE rẽ nhánh theo
  `data.code`.
- **`deviceId` là do client tự sinh và gửi lên, server không xác thực tính hợp lệ của nó** (ngoài
  so khớp với claim trong token/session) — chỉ đóng vai trò "dấu vân tay thiết bị do client tự
  khai", không phải cơ chế chống giả mạo thiết bị tuyệt đối.

---

*Chức năng dùng lại kiến trúc này: `4. Refresh token.md` (rotation + reuse-detection chi tiết),
`5. Logout.md`, `6. JwtAuthFilter.md`.*
