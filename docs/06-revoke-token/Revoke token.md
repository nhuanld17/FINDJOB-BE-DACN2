# Thu hồi token & quản lý session (Revoke Token)

> ⚠️ **Bản cập nhật 2026-08-09** — đối chiếu lại với code hiện tại
> (`AuthServiceImplement`, `JwtAuthFilter`, `SessionServiceImpl`, `TokenBlacklistServiceImpl`).
> Các điểm đã sửa so với bản draft cũ:
> - Field session đổi tên `refreshJtiCurrent` → **`currentRefreshJti`** (đồng bộ `SessionConstant`)
> - Sửa typo `blacklis:*` → **`blacklist:*`**
> - Login dùng **`email`** (không phải `username`)
> - Rotation **KHÔNG** blacklist refresh token cũ (chống reuse dựa trên `currentRefreshJti`)
> - Logout thiết bị hiện tại **xoá hẳn** `session:{sessionId}` (không set `REVOKED`)
> - `/logout-device` và `/logout-all` **chưa triển khai** (không có endpoint)
> - Bổ sung flow thực tế: **đổi mật khẩu → force logout các thiết bị khác**

---

## Claims trong access token và refresh token

Cả 2 token đều là JWT do backend ký, mang các claim:

- `jti`: định danh token (UUID — ngẫu nhiên mỗi lần cấp)
- `sub`: username (unique)
- `roles`: danh sách quyền (ROLE_USER / ROLE_COMPANY...)
- `sessionId`: định danh phiên đăng nhập của thiết bị
- `deviceId`: định danh thiết bị (client gửi lên lúc login)
- `iat` / `exp`: thời điểm phát hành / hết hạn

> `sessionId` + `deviceId` là 2 claim quan trọng nhất cho cơ chế thu hồi:
> mọi request đều được đối chiếu với dữ liệu session trong Redis (xem "Request thường").

## Cấu trúc key trong Redis (4 nhóm)

| Key | Kiểu | Nội dung | TTL |
|---|---|---|---|
| `session:{sessionId}` | Hash (9 field) | `username`, `deviceId`, `currentRefreshJti`, `status`, `createdAt`, `lastSeen`, `deviceName`, `ip`, `userAgent` | **7 ngày cố định** (fixed-window, không gia hạn khi refresh) |
| `user:sessions:{userId}` | Set | Các `sessionId` đang mở của user — phục vụ revoke all / liệt kê thiết bị | vô thời hạn (đi cùng session) |
| `blacklist:access:{jti}` | String (`"revoked"`) | Access token bị thu hồi | **= thời gian còn lại của access token** |
| `blacklist:refresh:{jti}` | String (`"revoked"`) | Refresh token bị thu hồi | **= thời gian còn lại của refresh token** |

Ví dụ session hash:

```text
session:s-uuid-001
  username=name-001
  deviceId=d-uuid-001
  currentRefreshJti=rjti-001
  status=ACTIVE              # hoặc REVOKED
  createdAt=...
  lastSeen=...
  deviceName=Chrome on Windows
  ip=1.2.3.4
  userAgent=Mozilla/5.0 ...

user:sessions:1 = { s-uuid-001, s-uuid-002 }
```

> Prefix (`session:`, `user:sessions:`, `blacklist:refresh:`, `blacklist:access:`) là **private**
> trong từng service (`SessionServiceImpl`, `TokenBlacklistServiceImpl`) — business layer chỉ gọi
> qua method, không bao giờ thấy key prefix.

---

## Login — tạo session

### 1. UI chuẩn bị thông tin

Frontend lấy hoặc tạo `deviceId` (UUID) rồi gửi kèm `email`, `password`, `deviceId`, `deviceName`.
`deviceId` là client-instance identifier, `deviceName` chủ yếu để hiển thị trong màn quản lý thiết bị.

```json
POST /api/v1/auth/login
{
  "email": "alice@example.com",
  "password": "123456",
  "deviceId": "d-uuid-001",
  "deviceName": "Chrome on Windows"
}
```

### 2. Backend xác thực và tạo session (`createUserSession`)

Sau khi email/password đúng (hoặc OAuth/OIDC thành công), backend:

1. `preLoginCheck` — user tồn tại + không bị ban.
2. Sinh `sessionId = UUID.randomUUID()` — **1 phiên = 1 thiết bị**.
3. Sinh `accessToken` (jti_access) + `refreshToken` (jti_refresh) — cả 2 đều mang `sub`, `jti`,
   `sessionId`, `deviceId`.
4. `sessionService.createSession(...)` — ghi Hash `session:{sessionId}` (9 field, `status=ACTIVE`),
   TTL 7 ngày.
5. `sessionService.addSessionToUser(...)` — thêm `sessionId` vào Set `user:sessions:{userId}`.
6. `writeCookie("refreshToken", ...)` — cookie HttpOnly, Secure, SameSite=Strict, path=/, 7 ngày.
7. Trả `AuthResponse` (accessToken + refreshToken trong body — mobile lưu SecureStore, web dùng cookie).

> **Ghi chú triển khai:** các thao tác Redis được thực hiện bằng `RedisTemplate` thường
> (nhiều lệnh riêng lẻ), **không dùng Lua script** — không có tính atomic toàn bộ, nhưng với
> thứ tự ghi hash → add index là đủ cho luồng login.

### 3. Trả token cho client

- **Access token** — nằm trong body response; client tự giữ để đính `Authorization: Bearer` mỗi request.
- **Refresh token** — nằm trong cookie `HttpOnly` (`Secure`, `SameSite=Strict`); trình duyệt tự
  quản; **mobile** nhận qua body và tự lưu.

---

## Request thường — `JwtAuthFilter` kiểm tra gì

Khi client gọi API protected, filter chạy theo thứ tự sau:

1. Parse claims từ access token (`sub`, `jti`, `sessionId`, `deviceId`).
   - Token **hết hạn** → `ACCESS_TOKEN_EXPIRED` (FE biết đường gọi refresh).
   - Token hỏng/sai chữ ký → `UNAUTHENTICATED`.
2. Check `blacklist:access:{jti}` — bị thu hồi → `TOKEN_REVOKED`.
3. Đọc toàn bộ Hash `session:{sessionId}` (1 lần HGETALL).
   - Session không tồn tại (hoặc hết TTL) → `SESSION_INACTIVE`.
4. Check `status == ACTIVE` — không phải → `SESSION_INACTIVE`.
5. **Đối chiếu ràng buộc thiết bị**:
   - `session.username == sub` (claim) — lệch → `UNAUTHENTICATED`.
   - `session.deviceId == deviceId` (claim) — lệch → `SESSION_DEVICE_MISMATCH`.
6. Load user từ DB: bị ban (deleted) → `ACCOUNT_BANNED`; chưa active → `USER_INACTIVE`.
7. `isTokenValid` → set `Authentication` vào SecurityContext, **update `lastSeen`**.

Pseudo-flow:

```text
Request -> parse access token
        -> check exp/signature
        -> check blacklist:access:{jti}
        -> check session:{sessionId} tồn tại & status == ACTIVE
        -> check session.username == sub & session.deviceId == deviceId(claim)
        -> check user (DB) chưa bị ban/vô hiệu
        -> allow + update lastSeen
```

> **Điểm mấu chốt:** khi revoke 1 thiết bị, toàn bộ access/refresh của session đó bị vô hiệu theo
> trạng thái/KEY session — không cần nhìn vào từng token đơn lẻ. Đây là lớp chặn cuối cùng
> (backstop) khi access token chưa kịp vào blacklist.

---

## Refresh token — rotation + reuse detection

Khi access token hết hạn, client gọi `/api/v1/auth/refresh-token` (web: cookie; mobile: body):

1. Parse claims từ refresh token — lỗi → xoá cookie + `UNAUTHORIZED`.
2. Check `blacklist:refresh:{jti}` → bị thu hồi → `TOKEN_REVOKED`.
3. Check `session:{sessionId}` tồn tại + `status == ACTIVE` → không → `SESSION_INACTIVE`.
4. **So khớp `currentRefreshJti`** (trong session) với `jti` của RT gửi lên:
   - Không khớp → **phát hiện reuse**:
     - set `session:{sessionId}.status = REVOKED` (giết cả phiên),
     - blacklist chính RT bị replay đó,
     - trả `TOKEN_REUSE_DETECTED` → buộc đăng nhập lại.
5. Check user: bị ban → `ACCOUNT_BANNED`; inactive → `USER_INACTIVE`; `isTokenValid` → không → `UNAUTHORIZED`.
6. **Rotation**:
   - Sinh access token mới (`jti_access_new`) + refresh token mới (`jti_refresh_new`).
   - ⚠️ **KHÔNG blacklist refresh token cũ** — nó tự hết hiệu lực vì `jti` không còn khớp
     `currentRefreshJti`. Nếu blacklist ở đây, guard `TOKEN_REVOKED` (bước 2) sẽ chặn trước và
     **làm mất khả năng phát hiện reuse** ở bước 4 (replay token lộ chỉ nhận `TOKEN_REVOKED` mà
     session vẫn ACTIVE). Đây là lựa chọn có chủ đích trong code.
   - Update `session:{sessionId}.currentRefreshJti = jti_refresh_new` + update `lastSeen`.
   - Ghi cookie refresh token mới (7 ngày).
7. Trả access + refresh token mới.

```text
refresh request
-> validate refresh token
-> check blacklist:refresh:{jti}
-> check session tồn tại & ACTIVE
-> compare jti == currentRefreshJti     (lệch → REVOKED session + TOKEN_REUSE_DETECTED)
-> issue new access + new refresh       (KHÔNG blacklist RT cũ)
-> save new currentRefreshJti
-> return new tokens
```

---

## Logout thiết bị hiện tại — `POST /api/v1/auth/logout`

Thiết kế **idempotent** (gọi bao nhiêu lần cũng không lỗi) và **best-effort** (thiếu gì thì bỏ qua,
không ném exception):

1. **Luôn xoá cookie `refreshToken`** (bước đầu tiên, không điều kiện).
2. RT null/blank → return ngay.
3. Đọc `Authorization: Bearer <accessToken>` từ header (tùy chọn — để tranh thủ blacklist AT).
4. Parse claim từ RT (`username`, `sessionId`, `remainingTimeOf`) — lỗi → return.
5. `session:{sessionId}` không tồn tại → return.
6. Đọc `currentRefreshJti` từ session.
7. **Xoá hẳn `session:{sessionId}`** (`deleteSession` — DEL key, **không** set `REVOKED`).
8. Gỡ `sessionId` khỏi `user:sessions:{userId}` — **best-effort**: không tra ra user thì bỏ qua.
9. Blacklist access token (nếu có `jti` + `remaining > 0`).
10. Blacklist refresh token theo **`currentRefreshJti`** (đọc từ session, không phải `jti` của RT
    trong request) với TTL = `remainingTimeOf(RT trong request)`.

Kết quả: thiết bị hiện tại không access tiếp được, không refresh được nữa.

> **Vì sao blacklist `currentRefreshJti` mà không phải `jti` gửi lên?** Logout thu hồi "bản RT mà
> hệ thống đang công nhận hợp lệ cho phiên này". Trong đa số trường hợp 2 giá trị giống nhau
> (client gửi đúng RT mới nhất). Dù sao session đã bị xoá ở bước 7, nên mọi RT của phiên đều chết
> ở guard "session không tồn tại" — blacklist chỉ là lớp phòng thủ bổ sung để chặn sớm hơn.

---

## Logout một thiết bị khác — ⚠️ CHƯA TRIỂN KHAI

> **Trạng thái:** chưa có endpoint (`/logout-device` chưa tồn tại). `SessionService.revokeSession()`
> (set `status=REVOKED`) đã có nhưng **chưa có caller**. Màn "quản lý thiết bị" phía client cũng
> chưa được xây.

Thiết kế dự kiến khi triển khai:

1. Backend nhận `sessionId` mục tiêu.
2. Kiểm tra session đó thuộc đúng user (`user:sessions:{userId}` chứa sessionId).
3. Đọc `session:{sessionId}` → set `status = REVOKED` (hoặc xoá hẳn như logout hiện tại).
4. Blacklist `currentRefreshJti` của session đó.
5. Access token đang sống của thiết bị đó sẽ chết khi hết hạn ngắn (hoặc qua `SESSION_INACTIVE`
   khi session không còn ACTIVE).

Thiết bị A revoke thiết bị B — session của A vẫn nguyên vì mỗi thiết bị có `sessionId` riêng.

---

## Đổi mật khẩu → force logout các thiết bị khác (ĐÃ TRIỂN KHAI)

Đây là flow "revoke nhiều thiết bị" **duy nhất hiện có trong code** (`changePassword` →
`forceLogoutOtherDevices`), kích hoạt khi user đổi/đặt mật khẩu:

1. Cập nhật password mới trong DB (JPQL update).
2. Lấy `sessionId` của **thiết bị hiện tại** từ `Authorization` header (fail-closed: không lấy
   được → coi như revoke TẤT CẢ).
3. Lấy toàn bộ session từ `user:sessions:{userId}`.
4. Với mỗi session **khác thiết bị hiện tại**:
   - Blacklist `currentRefreshJti` của session đó (TTL = TTL còn lại của session).
   - **Xoá hẳn** `session:{sessionId}` + gỡ khỏi `user:sessions:{userId}`.
5. Thiết bị hiện tại giữ nguyên (access token còn sống, vẫn refresh được).

> Luồng "Logout all" (đăng xuất tất cả thiết bị) **chưa có endpoint riêng** — nếu sau này cần,
> hạ tầng đã sẵn (`getUserSessions`, `deleteSession`, `deleteAllUserSessionsIndex`), chỉ cần
> thêm controller giống `forceLogoutOtherDevices` nhưng không loại trừ thiết bị hiện tại.

---

## Trạng thái lỗi và thực tế triển khai

- **Access token đã logout nhưng chưa kịp blacklist (Redis lỗi):** session đã bị xoá →
  `JwtAuthFilter` trả `SESSION_INACTIVE` cho mọi request dùng token đó. Session state là lớp chặn
  quan trọng nhất — blacklist chỉ là lớp bổ sung.
- **Session metadata chỉ nằm ở Redis** (không lưu Postgres) → không có bài toán
  "DB commit xong Redis fail" kiểu outbox. Nếu Redis mất dữ liệu, mọi session coi như hết hạn —
  user phải đăng nhập lại (fail-closed, an toàn).
- **Refresh token bị replay:** `jti` không khớp `currentRefreshJti` → session bị set `REVOKED` +
  trả `TOKEN_REUSE_DETECTED` → cả phiên chết (thiết kế "giết cả family").
- **TTL session = 7 ngày cố định, không gia hạn khi refresh** — chỉ `currentRefreshJti` và
  `lastSeen` được cập nhật. Hết 7 ngày dù token còn hạn cũng phải đăng nhập lại.

---

## Tóm tắt luồng

1. **Login**
   - UI gửi `email/password/deviceId/deviceName`.
   - Server tạo `sessionId`, access token, refresh token.
   - Redis lưu `session:{sessionId}` (9 field, TTL 7 ngày) + add vào `user:sessions:{userId}`.
2. **Gọi API**
   - Filter kiểm tra access token: hết hạn → `ACCESS_TOKEN_EXPIRED`; blacklist `jti` →
     `TOKEN_REVOKED`; session tồn tại + `ACTIVE` + khớp `username`/`deviceId` → cho qua + update
     `lastSeen`.
3. **Refresh**
   - Kiểm tra RT: blacklist → `TOKEN_REVOKED`; so khớp `currentRefreshJti` → lệch = reuse →
     `TOKEN_REUSE_DETECTED` + revoke session.
   - Rotate: cấp AT/RT mới, KHÔNG blacklist RT cũ, cập nhật `currentRefreshJti` + `lastSeen`.
4. **Logout 1 thiết bị (hiện tại)**
   - Xoá cookie, xoá hẳn `session:{sessionId}`, gỡ khỏi `user:sessions:{userId}`, blacklist AT +
     `currentRefreshJti`. (Logout thiết bị KHÁC: chưa triển khai.)
5. **Đổi mật khẩu**
   - Force logout tất cả thiết bị khác: blacklist `currentRefreshJti` từng session + xoá session
     + gỡ index. (Endpoint "logout all" riêng: chưa triển khai.)
