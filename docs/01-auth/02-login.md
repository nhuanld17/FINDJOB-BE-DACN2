# Đăng nhập (Login)

> Tài liệu cho **một chức năng**: đăng nhập bằng **email + password**.
> Endpoint có **2 luồng** rẽ theo trạng thái tài khoản:
> - Tài khoản **đã kích hoạt** → cấp access/refresh token + tạo session.
> - Tài khoản **chưa kích hoạt** → không cấp token, mà trả về trạng thái OTP để FE dẫn user đi
>   verify (tái dùng đúng "4 đồng hồ OTP" của luồng đăng ký).
>
> Bám sát code: `AuthServiceImplement.login` + `handleInactiveUserLogin` + `LoginRequest` +
> `AuthResponse`/`LoginInactiveResponse` + `SessionService.createSession`. Cập nhật: 2026-07-05.
>
> 📎 Doc này dùng lại khái niệm **attempts / wrong / otp / cooldown / pending token** đã mô tả kỹ
> trong `1. Register & OTP.md` (mục *"Bốn đồng hồ OTP"*). Ở đây chỉ nhắc lại phần liên quan.

## Mục lục
1. [Toàn cảnh — 2 luồng](#1-toàn-cảnh--2-luồng)
2. [Request & validation](#2-request--validation)
3. [Luồng ACTIVE — đăng nhập thành công](#3-luồng-active--đăng-nhập-thành-công)
4. [Luồng INACTIVE — tài khoản chưa kích hoạt](#4-luồng-inactive--tài-khoản-chưa-kích-hoạt)
5. [Bảng tra mã](#5-bảng-tra-mã)
6. [Ghi chú & điểm dễ nhầm](#6-ghi-chú--điểm-dễ-nhầm)

---

## 1. Toàn cảnh — 2 luồng

`POST /api/v1/auth/login` (endpoint **public**, không qua `JwtAuthFilter`). Trả về kiểu
`LoginResult` — một *sealed interface* có 2 hiện thực:

- **`AuthResponse`** — khi đăng nhập thành công (có `accessToken`).
- **`LoginInactiveResponse`** — khi tài khoản chưa active (mang code + thông tin OTP).

Cả hai đều được bọc trong `APIResponse` (nhớ "2 tầng code": `code=1000` là envelope, `data.code`
mới là nghiệp vụ).

```
login(email, password, deviceId, deviceName, pendingToken?)
  │
  ├─ authenticate(email, password)
  │     ├─ sai email/mật khẩu   → throw 3008 INVALID_CREDENTIALS
  │     └─ lỗi auth khác        → throw 3001 UNAUTHENTICATED
  │
  ├─ isDeleted?                 → throw 2007 ACCOUNT_BANNED
  │
  ├─ !isActive?  ──────────────► handleInactiveUserLogin(...)
  │                              → LoginInactiveResponse (HTTP 200, code 4002/4003/4004/4005/100x)
  │
  └─ active  ──────────────────► tạo session + token
                                 → AuthResponse (data.code = 4001) + cookie refreshToken
```

> **Điểm hay:** trạng thái nghiệp vụ (`isDeleted`/`isActive`) chỉ được kiểm **sau khi mật khẩu
> đúng**. Nghĩa là kẻ tấn công không thể dò tài khoản có bị khóa/chưa active hay không nếu không
> có mật khẩu hợp lệ. (Xem thêm §6 về lý do không bắt `LockedException`/`DisabledException`.)

---

## 2. Request & validation

`LoginRequest` (record):

| Field | Kiểu | Ràng buộc | Lỗi validation |
|---|---|---|---|
| `email` | String | `@NotBlank`, `@Email`, `@Size(max=254)` | `1001` BLANK / `1003` INVALID_EMAIL |
| `password` | String | `@NotBlank`, `@Size(min=8, max=1000)` | `1001` BLANK / `1006` INVALID_PASSWORD |
| `deviceId` | UUID | `@NotNull` | `1001` BLANK |
| `deviceName` | String | `@NotBlank`, `@Size(max=100)` | `1001` BLANK / `1002` OUT_OF_SIZE |

`pendingToken` (tùy chọn) — cookie web hoặc header `X-Pending-Token` mobile — chỉ dùng cho
**luồng inactive** (để tái dùng/xoay phiên OTP). **Dual-mode:** server ưu tiên header, fallback cookie.

Trước khi xử lý: email được chuẩn hóa `trim().toLowerCase()`, password `trim()`.

> **`deviceId` & `deviceName`:** client tự tạo/lưu `deviceId` (UUID, định danh **instance thiết
> bị**) và gửi kèm mỗi lần login; `deviceName` chỉ để hiển thị ở màn "thiết bị đã đăng nhập".
> `deviceId` sẽ được nhúng vào token và lưu trong session để sau này gắn phiên với đúng thiết bị.

---

## 3. Luồng ACTIVE — đăng nhập thành công

Sau khi `authenticate` OK và tài khoản active (`isDeleted=false`, `isActive=true`):

1. **Sinh `sessionId`** = UUID mới (đại diện 1 phiên đăng nhập của 1 thiết bị).
2. **Sinh 2 token** bằng `JwtUtil` — cùng bộ claim, chỉ khác `exp` & `jti`:
   - `accessToken` (sống ngắn), `refreshToken` (sống 7 ngày).
   - Claim mỗi token: `jti` (UUID), `sub` = **username**, `roles`, `sessionId`, `deviceId`, `iat`, `exp`.
3. **Lưu session vào Redis** — `sessionService.createSession(...)` ghi hash `session:{sessionId}`:

   | Field | Giá trị |
   |---|---|
   | `username` | username của user |
   | `deviceId` | từ request |
   | `currentRefreshJti` | `jti` của **refresh token** vừa cấp |
   | `status` | `ACTIVE` |
   | `createdAt` / `lastSeen` | thời điểm hiện tại |
   | `deviceName` / `ip` / `userAgent` | metadata thiết bị & request |

   TTL session = **7 ngày, fixed-window** (không gia hạn khi refresh → giới hạn tuyệt đối 7 ngày
   kể từ lúc login, khớp hạn của refresh token & cookie).
4. **Thêm vào danh sách phiên của user**: `addSessionToUser(userId, sessionId)` → set `user:sessions:{userId}`.
5. **Ghi cookie `refreshToken`**: `HttpOnly`, `Secure`, `SameSite=Strict`, `path=/`, `maxAge=7 ngày`.
6. **Ghi cookie `refreshToken`** (web) — mobile không cần vì đã có `data.refreshToken`.
7. **Trả `AuthResponse`**:

```jsonc
{
  "code": 1000, "message": "Success",
  "data": {
    "code": 4001,              // LOGIN_SUCCESS
    "id": 123,
    "username": "alice01",
    "roles": [ ... ],
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",  // null với web (chỉ cookie), có giá trị với mobile
    "hasPassword": true              // false với user Google (password = NULL) — FE dùng để
                                      // quyết định form đổi mk (3 field) hay đặt mk lần đầu (2 field)
  }
}
```

> **Dual-mode token:** access token nằm ở body (cả web lẫn mobile). **Refresh token:** web nhận qua
> cookie HttpOnly (`data.refreshToken = null`, JS không đọc được → giảm rủi ro XSS); **mobile
> nhận `data.refreshToken` trong body** và lưu secure store, gửi lại bằng body khi refresh/logout.

---

## 4. Luồng INACTIVE — tài khoản chưa kích hoạt

Khi mật khẩu đúng nhưng `isActive=false` (đăng ký rồi nhưng chưa verify OTP), login **không ném
lỗi** mà gọi `handleInactiveUserLogin(...)` → trả `LoginInactiveResponse` (**HTTP 200**), mang
`data.code` + thông tin đếm ngược, để FE **chuyển thẳng sang trang verify OTP**.

Nó dùng lại **đúng "4 đồng hồ OTP"** (attempts / wrong / otp / cooldown) như register, rẽ nhánh
theo thứ tự **attempts → wrong → otp → cooldown**:

| attempts | wrong | otp | cooldown | `data.code` | Cấp cookie `pendingToken`? | Hành động |
|:--:|:--:|:--:|:--:|:--:|:--:|---|
| ≥5 | ≥5 | – | – | **1010** | ✗ | BLOCK — chờ TTL `attempts` rồi đăng ký lại |
| ≥5 | <5 | còn hạn | >0 | **4004** | ✓ | tái dùng OTP cũ (đang cooldown) |
| ≥5 | <5 | còn hạn | =0 | **1008** | ✓ | tái dùng OTP cũ |
| ≥5 | <5 | hết hạn | – | **1011** | ✗ | BLOCK — chờ TTL `attempts` |
| <5 | ≥5 | – | – | **1006** | ✗ | bảo user bấm **resend** |
| <5 | <5 | còn hạn | >0 | **4003** | ✓ | tái dùng OTP cũ (đang cooldown) |
| <5 | <5 | còn hạn | =0 | **4005** | ✓ | tái dùng OTP cũ |
| **<5** | **<5** | **hết hạn** | – | **4002 ⭐** | ✓ | **PHÁT OTP MỚI + gửi email** |

- Chỉ nhánh **4002** thực sự sinh mã mới: `rotatePendingToken` → `saveOtp` → `setCooldown` →
  `incrementAttempts` → `resetWrong` → `sendOtpEmail`.
- Các nhánh "tái dùng" (4004/1008/4003/4005) gọi `resolveOrCreatePendingToken` + ghi cookie để
  giữ nguyên phiên OTP đang có.
- Các nhánh BLOCK (1010/1011/1006) **không** cấp `pendingToken` mới — FE chỉ hiển thị thông báo
  chờ.

`LoginInactiveResponse` mang kèm (tùy nhánh): `otpExpiresIn`, `cooldownRemaining`,
`wrongRemaining`, `attemptsTTL`.

> **Vì sao 4 mã 4002–4005 tách riêng khỏi 100x?** Đây là các code *đặc thù cho login-inactive*
> để FE phân biệt "vừa đến từ màn login" với "đang ở màn đăng ký". Còn 1006/1008/1010/1011 là
> các mã **dùng chung** với register/verify/resend (cùng ý nghĩa trạng thái).

---

## 5. Bảng tra mã

### SuccessCode (ở `data.code`, HTTP luôn 200)

| Code | Tên | Luồng |
|:--:|---|---|
| 4001 | LOGIN_SUCCESS | ACTIVE — đăng nhập thành công |
| 4002 | LOGIN_INACTIVE_OTP_SENT | INACTIVE — đã phát OTP mới ⭐ |
| 4003 | LOGIN_INACTIVE_OTP_REUSED | INACTIVE — tái dùng OTP (đang cooldown) |
| 4004 | OTP_ATTEMPTS_LIMIT_REACHED_AND_COOLDOWN_ACTIVE | INACTIVE — attempts≥5, tái dùng OTP (cooldown) |
| 4005 | LOGIN_INACTIVE_OTP_REUSED_NO_COOLDOWN | INACTIVE — tái dùng OTP (hết cooldown) |
| 1006 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED | INACTIVE — nhập sai đủ 5 → resend |
| 1008 | ...OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED | INACTIVE — attempts≥5, OTP còn hạn |
| 1010 | OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED | INACTIVE — block |
| 1011 | OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED | INACTIVE — block |

### ErrorCode (ném qua `AppException` → `ErrorResponse`)

| Code | Tên | HTTP | Khi nào |
|:--:|---|:--:|---|
| 1001–1006 | *(validation)* BLANK / SIZE / EMAIL / PASSWORD | 400 | `@Valid` trên `LoginRequest` |
| 3008 | INVALID_CREDENTIALS | 401 | sai email hoặc mật khẩu (`BadCredentialsException`) |
| 3001 | UNAUTHENTICATED | 401 | lỗi xác thực khác |
| 2007 | ACCOUNT_BANNED | 403 | tài khoản bị ban (`isDeleted=true`) |

---

## 6. Ghi chú & điểm dễ nhầm

- **Đăng nhập bằng email, nhưng token mang `sub = username`.** `authenticate` tra user theo email
  (`loadUserByUsername(email)`), còn `CustomUserDetails.getUsername()` trả về **username** → JWT
  `sub` = username. Mọi chỗ tra user về sau (filter, refresh) đều dùng username, nhất quán.
- **Không bắt `LockedException`/`DisabledException` lúc `authenticate`** — có chủ ý: `CustomUserDetails`
  để `isEnabled()`/`isAccountNonLocked()` luôn `true`, nên `DaoAuthenticationProvider` không tự ném
  2 lỗi đó. Trạng thái `isDeleted`/`isActive` được kiểm **thủ công sau** khi mật khẩu đúng, để
  không lộ trạng thái tài khoản khi chưa có credential hợp lệ.
- **Mỗi lần login tạo một `sessionId` mới** và `addSessionToUser` **không dedup theo `deviceId`**.
  Nghĩa là cùng một thiết bị login lại (khi chưa logout) sẽ **cộng dồn** session ("ghost session").
  Đây là **quyết định đã chấp nhận** (dựa vào contract FE: user còn token thì FE tự điều hướng,
  không submit login lại); ghost session tự hết sau TTL 7 ngày. Nếu sau này muốn "1 thiết bị = 1
  phiên" thì duyệt `user:sessions:{userId}`, so `deviceId`, revoke phiên cũ trước khi tạo phiên mới.
- **`@Transactional(readOnly=true)`** chỉ áp cho tầng DB (đọc user). Việc ghi Redis (`createSession`,
  `addSessionToUser`) và gửi mail (nhánh 4002) nằm ngoài phạm vi transaction đó — nhãn `readOnly`
  hơi gây hiểu nhầm nhưng không sai về mặt DB.
- **Refresh token & cookie & session TTL đồng bộ 7 ngày** — cả 3 cùng hết hạn một mốc, nên không
  có tình trạng "cookie còn mà session chết" do lệch hạn (chỉ chết sớm khi logout / revoke).

---

*Chức năng tiếp theo: `3. Refresh token.md` (rotation + reuse-detection) và `4. Logout.md`.*
