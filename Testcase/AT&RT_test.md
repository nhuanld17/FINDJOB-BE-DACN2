# TEST CASES — ACCESS TOKEN & REFRESH TOKEN

Bộ test tay (Postman / curl) cho toàn bộ vòng đời **Access Token (AT)** và **Refresh Token (RT)**:
cấp token (**login**) · gác cổng AT (**JwtAuthFilter**) · xoay & chống replay RT (**/refresh-token**) · thu hồi (**/logout**).

Code trả về theo 2 khuôn:
- **Thành công** (login/refresh): HTTP 200, bọc trong `APIResponse` → `data` chứa `AuthResponse` (`code`, `id`, `username`, `roles`, `accessToken`). **RT nằm ở cookie `Set-Cookie`, không có trong body.**
- **Lỗi**: `GlobalExceptionHandler` (cho `/refresh-token`, `/logout`) và `JwtAuthEntryPoint` (cho AT trong filter) đều trả cùng format `ErrorResponse.of(status, code, message)`.

## Mục lục
- [Nhóm 0: Quy ước & Setup](#nhóm-0-quy-ước--setup)
- [Nhóm 1: LOGIN — cấp AT + RT](#nhóm-1-login--cấp-at--rt)
- [Nhóm 2: ACCESS TOKEN — JwtAuthFilter](#nhóm-2-access-token--jwtauthfilter)
- [Nhóm 3: REFRESH TOKEN — /refresh-token](#nhóm-3-refresh-token--refresh-token)
- [Nhóm 4: LOGOUT — thu hồi token](#nhóm-4-logout--thu-hồi-token)
- [Nhóm 5: Kịch bản kết hợp (E2E)](#nhóm-5-kịch-bản-kết-hợp-e2e)
- [Phụ lục A — Bảng tra ErrorCode](#phụ-lục-a--bảng-tra-errorcode)
- [Phụ lục B — Checklist xác minh sau mỗi TC](#phụ-lục-b--checklist-xác-minh-sau-mỗi-tc)

---

## Nhóm 0: Quy ước & Setup

### 0.1 Endpoint
| Hàm | Method | URL | Auth |
|---|---|---|---|
| login | POST | `http://localhost:8080/api/v1/auth/login` | public |
| refresh-token | POST | `http://localhost:8080/api/v1/auth/refresh-token` | public (đọc cookie `refreshToken`) |
| logout | POST | `http://localhost:8080/api/v1/auth/logout` | public (đọc cookie `refreshToken` + header `Authorization`) |
| **endpoint protected** | GET | `http://localhost:8080/api/v1/test/ping` | **cần AT** (xem 0.5) |

> **Lưu ý quan trọng:** `SecurityConfig.PUBLIC_PATTERNS` để `/api/v1/auth/**` là public → **`JwtAuthFilter` KHÔNG chạy** trên login/refresh/logout. Vì vậy muốn test Access Token phải bắn vào **một path protected** (bất kỳ path nào KHÔNG nằm trong `PUBLIC_PATTERNS`).

### 0.2 Cookie
- `refreshToken`: `httpOnly`, `secure=true`, `SameSite=Strict`, path `/`, MaxAge `7 ngày`. Chỉ xuất hiện khi **login thành công** (tài khoản active) và mỗi lần **refresh thành công** (token rotation).
- Vì `secure=true`, trên `http://localhost` một số trình duyệt vẫn gửi (localhost được miễn), nhưng **Postman/curl là chắc chắn nhất** vì ta set cookie thủ công.

### 0.3 Cấu trúc claim trong JWT (dán token vào https://jwt.io để đọc)
`buildToken()` nhét: `sub` = username · `jti` (UUID) · `sessionId` (UUID) · `deviceId` (UUID) · `roles` · `iat` · `exp`.
AT và RT **giống hệt claim**, chỉ khác `exp` (AT ngắn, RT dài) và `jti` (khác nhau).

### 0.4 Cấu trúc session trong Redis (`RedisService.createSession`)
Hash `session:{sessionId}` gồm các field:
`username` · `deviceId` · `refreshJtiCurrent` · `status` (`ACTIVE`/`REVOKED`) · `createdAt` · `lastSeen` · `deviceName` · `ip` · `userAgent`. TTL cố định **7 ngày** (không gia hạn khi refresh — fixed window).

**Lệnh redis-cli hay dùng:**
```bash
redis-cli KEYS "session:*"                 # tìm sessionId đang sống
redis-cli HGETALL session:<sid>            # xem toàn bộ field session
redis-cli HGET  session:<sid> refreshJtiCurrent   # jti RT hiện hành
redis-cli KEYS "blacklist:*"               # blacklist:access:<jti> / blacklist:refresh:<jti>
redis-cli SMEMBERS user:sessions:<userId>  # danh sách session của user
redis-cli TTL session:<sid>                # còn ~604800s = 7 ngày

# Ép state để chạm nhánh khó:
redis-cli DEL  session:<sid>                       # session biến mất
redis-cli HSET session:<sid> status REVOKED        # session không active
redis-cli HDEL session:<sid> refreshJtiCurrent     # mất jti hiện hành
redis-cli HSET session:<sid> username hacker       # username lệch token
redis-cli HSET session:<sid> deviceId 99999999-9999-9999-9999-999999999999  # device lệch
```
> Lấy `<sid>` bằng cách decode token trên jwt.io (claim `sessionId`), hoặc `redis-cli KEYS "session:*"` khi chỉ có 1 phiên.

### 0.5 Tạo endpoint protected để test AT (khuyến nghị)
App hiện chỉ có `AuthController` (toàn bộ public) nên **chưa có path protected sạch**. Thêm tạm 1 controller (xóa sau khi test):
```java
@RestController
@RequestMapping("/api/v1/test")
class PingController {
    @GetMapping("/ping")
    public ResponseEntity<?> ping(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.example.boilerplate.infrastructure.security.CustomUserDetails user) {
        return ResponseEntity.ok(java.util.Map.of("username", user.getUsername()));
    }
}
```
→ Token hợp lệ trả `200 {"username": "..."}`; token lỗi trả `401/403` kèm `code`.

> **Không muốn thêm code?** Dùng luôn path ảo protected, ví dụ `GET /api/v1/whoami`:
> - AT **hợp lệ** → filter cho qua → DispatcherServlet không tìm thấy handler → **404 `{ "status":404, "message":"Resource not found" }`**. 404 này chính là dấu hiệu AT đã qua filter.
> - AT **lỗi** → **401/403 kèm `code`** ngay tại filter (chưa tới dispatcher).

### 0.6 Body login chuẩn (dùng lại xuyên suốt)
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```
> Điều kiện tiên quyết chung: tài khoản `alice@example.com` đã **active** (đăng ký + verify OTP xong). Nếu chưa, chạy flow đăng ký ở bộ test OTP trước.

### 0.7 Ép Access Token hết hạn nhanh (cho TC-AT-05)
AT/RT lấy TTL từ biến môi trường `ACCESS_TOKEN_LIFETIME` / `REFRESH_TOKEN_LIFETIME` (ms). Để test AT hết hạn mà không phải chờ:
```bash
# đặt AT sống 3 giây rồi khởi động lại app
ACCESS_TOKEN_LIFETIME=3000
REFRESH_TOKEN_LIFETIME=604800000
```
Login → chờ >3s → bắn AT vào endpoint protected. Nhớ **trả lại giá trị cũ** sau khi test.

### 0.8 Khuôn response mẫu
**Login/refresh thành công (200):**
```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "code": 4001,
    "id": 1,
    "username": "alice01",
    "roles": [ { "name": "USER" } ],
    "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
  }
}
```
**Lỗi (ví dụ reuse):**
```json
{ "status": 401, "code": 3013, "message": "Token reuse detected", "timestamp": "2026-07-02T..." }
```
**Lỗi không kèm code** (khi vào path protected mà KHÔNG gửi token — nhánh fallback của entrypoint):
```json
{ "status": 401, "message": "Full authentication is required to access this resource", "timestamp": "..." }
```

---

## Nhóm 1: LOGIN — cấp AT + RT

### TC-LOGIN-01: Đăng nhập hợp lệ → 4001, sinh AT + RT + session

**Mục tiêu:** Xác nhận `AuthServiceImplement.login` sinh đủ AT, RT, session Redis và set cookie RT.

**Điều kiện tiên quyết:** `alice@example.com` active; Redis không còn session cũ của user này (tùy chọn: `redis-cli DEL user:sessions:<userId>`).

**Request body:** (body login chuẩn — 0.6)

**Các bước thực hiện:**
1. POST `/login` với body chuẩn.
2. Đọc response body, header `Set-Cookie`, và Redis.
3. Dán `data.accessToken` vào jwt.io để xem claim.

**Kỳ vọng:**
- HTTP 200, `data.code = 4001`, có `accessToken`, `id`, `username`, `roles`.
- Header `Set-Cookie: refreshToken=...` (HttpOnly, Secure, SameSite=Strict, Max-Age=604800).
- AT claim có đủ `sub`, `jti`, `sessionId`, `deviceId = 11111111-...`, `roles`, `exp`.
- Redis: `session:{sid}` tồn tại, `status=ACTIVE`, `deviceId` = deviceId trong request, `refreshJtiCurrent` = **jti của RT** (không phải AT). `user:sessions:{userId}` chứa `{sid}`.
- **Không** đụng bất kỳ key OTP nào.

**Điểm verify đặc biệt:** `HGET session:{sid} refreshJtiCurrent` phải bằng `jti` của **refresh token** (decode RT từ cookie), KHÁC với `jti` của access token.

---

## Nhóm 2: ACCESS TOKEN — JwtAuthFilter

Tất cả TC nhóm này bắn vào **endpoint protected** (0.5), header `Authorization: Bearer <accessToken>`.
Thứ tự guard trong `doFilterInternal`: **parse claim → blacklist AT → session tồn tại → status ACTIVE → username khớp → deviceId khớp → user (deleted/inactive) → isTokenValid**.

### TC-AT-01: AT hợp lệ → 200 (qua filter)

**Điều kiện tiên quyết:** Vừa login (TC-LOGIN-01), có `accessToken` còn hạn.

**Các bước:**
1. GET `/api/v1/test/ping` với header `Authorization: Bearer <accessToken>`.

**Kỳ vọng:** HTTP 200 `{"username":"alice01"}` (hoặc 404 "Resource not found" nếu dùng path ảo — vẫn nghĩa là AT qua filter). Redis: `session:{sid}.lastSeen` được cập nhật mới.

---

### TC-AT-02: Không gửi Authorization → 401 (fallback, không code)

**Các bước:** GET `/api/v1/test/ping` **không** có header Authorization.

**Kỳ vọng:** HTTP 401, body **không có `code`** (nhánh `filterChain.doFilter` bỏ qua → Spring Security chặn → entrypoint fallback). Message kiểu "Full authentication is required...".

---

### TC-AT-03: Header sai tiền tố (không "Bearer ") → 401 (fallback)

**Các bước:** GET `/ping` với header `Authorization: Token abc.def.ghi`.

**Kỳ vọng:** HTTP 401 fallback (filter coi như không có token vì `!startsWith("Bearer ")`, cho qua → bị chặn ở tầng authorize).

---

### TC-AT-04: AT sai chữ ký / malformed → UNAUTHENTICATED 3001

**Các bước:**
1. Lấy AT hợp lệ, **sửa 1 ký tự** ở phần cuối (chữ ký), hoặc dùng chuỗi rác `abc.def.ghi`.
2. GET `/ping` với `Authorization: Bearer <token đã sửa>`.

**Kỳ vọng:** HTTP 401, `code = 3001` (Unauthenticated) — rơi vào `catch (Exception)` khi parse claim.

---

### TC-AT-05: AT hết hạn → ACCESS_TOKEN_EXPIRED 3015

**Điều kiện tiên quyết:** Set `ACCESS_TOKEN_LIFETIME=3000`, restart app (0.7), login lại lấy AT mới.

**Các bước:**
1. Chờ **> 3 giây**.
2. GET `/ping` với AT vừa lấy.

**Kỳ vọng:** HTTP 401, `code = 3015` (Access token expired) — nhánh `catch (ExpiredJwtException)`. Đây là code riêng để FE biết đường gọi `/refresh-token`.

---

### TC-AT-06: AT đã bị thu hồi (sau logout) → TOKEN_REVOKED 2010

**Điều kiện tiên quyết:** Login → **logout có gửi kèm `Authorization: Bearer <AT>`** (xem TC-LO-01) để AT bị đưa vào `blacklist:access:{jti}`. Giữ lại AT đó.

**Các bước:**
1. GET `/ping` với AT đã logout.

**Kỳ vọng:** HTTP 401, `code = 2010` (Token has been revoked) — nhánh `isAccessTokenRevoked`. Kiểm tra `redis-cli KEYS "blacklist:access:*"` có key.

> **Ghi chú thiết kế:** `/refresh-token` **không** blacklist AT cũ **lẫn RT cũ** — rotation dựa trên `refreshJtiCurrent`, không phải blacklist. Nên sau khi refresh, AT cũ vẫn dùng được đến khi hết hạn tự nhiên; RT cũ thì hết hiệu lực vì `jti` không còn khớp `refreshJtiCurrent` (bị bắt ở nhánh reuse, không phải blacklist). Chỉ **logout** mới đưa AT/RT vào blacklist.

---

### TC-AT-07: Session bị xóa → SESSION_INACTIVE 3012

**Điều kiện tiên quyết:** Login, lấy AT + `sid`.

**Các bước:**
1. `redis-cli DEL session:<sid>`.
2. GET `/ping` với AT.

**Kỳ vọng:** HTTP 401, `code = 3012` (Session inactive) — nhánh `session.isEmpty()`.

---

### TC-AT-08: Session status ≠ ACTIVE → SESSION_INACTIVE 3012

**Các bước:**
1. `redis-cli HSET session:<sid> status REVOKED`.
2. GET `/ping` với AT.

**Kỳ vọng:** HTTP 401, `code = 3012` — nhánh `!"ACTIVE".equals(status)`.

---

### TC-AT-09: username trong session ≠ sub của token → UNAUTHENTICATED 3001

**Các bước:**
1. `redis-cli HSET session:<sid> username hacker`.
2. GET `/ping` với AT (sub vẫn là `alice01`).

**Kỳ vọng:** HTTP 401, `code = 3001` — nhánh so khớp `username`. (Chống dùng AT của session khác.)

---

### TC-AT-10: deviceId claim ≠ deviceId session → SESSION_DEVICE_MISMATCH 3016

**Các bước:**
1. `redis-cli HSET session:<sid> deviceId 99999999-9999-9999-9999-999999999999`.
2. GET `/ping` với AT (deviceId claim = `11111111-...`).

**Kỳ vọng:** HTTP 401, `code = 3016` (Session device mismatch).

---

### TC-AT-11: User bị ban giữa chừng → ACCOUNT_BANNED 2007

**Điều kiện tiên quyết:** Login xong, session ACTIVE, AT còn hạn.

**Các bước:**
1. DB: set `is_deleted = true` cho user (`UPDATE users SET is_deleted=true WHERE email='alice@example.com';`).
2. GET `/ping` với AT.

**Kỳ vọng:** HTTP 403, `code = 2007` — filter load user tươi từ DB, phát hiện `isDeleted`.

> Dọn dẹp: trả `is_deleted=false` sau test.

---

### TC-AT-12: User bị vô hiệu hóa giữa chừng → USER_INACTIVE 2005

**Các bước:**
1. DB: `UPDATE users SET is_active=false WHERE email='alice@example.com';`.
2. GET `/ping` với AT.

**Kỳ vọng:** HTTP 403, `code = 2005` (User account is inactive).

> Dọn dẹp: trả `is_active=true`.

---

## Nhóm 3: REFRESH TOKEN — /refresh-token

RT nằm ở **cookie `refreshToken`**. Postman tự lưu cookie sau login. Để test các nhánh cần RT cụ thể (reuse), phải **copy giá trị cookie thủ công** trước khi nó bị xoay.
Thứ tự guard trong `refreshToken()`: **cookie tồn tại → parse claim → blacklist RT → session tồn tại → status ACTIVE → refreshJtiCurrent tồn tại → jti khớp (chống reuse) → user (deleted/inactive) → isTokenValid → cấp mới + rotate**.

### TC-RT-01: Refresh hợp lệ → 4001, xoay RT + cấp AT mới

**Điều kiện tiên quyết:** Vừa login (TC-LOGIN-01), cookie `refreshToken` còn sống. Ghi lại `RT_1` và `jti_1` (decode).

**Các bước:**
1. POST `/refresh-token` (Postman tự gửi cookie `refreshToken=RT_1`).

**Kỳ vọng:**
- HTTP 200, `data.code = 4001`, có `accessToken` **mới**.
- Header `Set-Cookie: refreshToken=RT_2` (khác `RT_1`) — token rotation.
- Redis: `session:{sid}.refreshJtiCurrent` = `jti_2` (jti của RT_2, khác `jti_1`); `lastSeen` cập nhật.
- `blacklist:refresh:{jti_1}` được tạo (RT cũ bị thu hồi).

**Điểm verify đặc biệt:** so `refreshJtiCurrent` trước/sau — phải đổi từ `jti_1` sang `jti_2`.

---

### TC-RT-02: Không có cookie refreshToken → UNAUTHORIZED 3009

**Các bước:** POST `/refresh-token` **không** gửi cookie (Postman: xóa cookie, hoặc dùng tab ẩn danh/curl không cookie).

**Kỳ vọng:** HTTP 401, `code = 3009` (Unauthorized). Response có `Set-Cookie` xóa `refreshToken` (Max-Age=0).

---

### TC-RT-03: Cookie rác (không parse được) → UNAUTHORIZED 3009

**Các bước:** POST `/refresh-token` với header `Cookie: refreshToken=this.is.garbage`.

**Kỳ vọng:** HTTP 401, `code = 3009` — nhánh `catch` khi extract claim thất bại. Cookie bị xóa.

---

### TC-RT-04: RT đã bị thu hồi → TOKEN_REVOKED 2010

**Mục tiêu:** Xác nhận guard `isRefreshTokenRevoked(jti)` chặn mọi RT có `jti` nằm trong `blacklist:refresh:{jti}`, và guard này chạy **trước** guard session — bất kể session còn hay mất.

**Bối cảnh RT bị blacklist (3 nguồn — code):** một `jti` RT bị đưa vào `blacklist:refresh` khi:
1. **Logout** (`logout()`): blacklist `refreshJtiCurrent` của session (TC-LO-01).
2. **Refresh thành công** (`refreshToken()`): blacklist `jti` của RT **cũ** vừa bị xoay (TC-RT-01).
3. **Phát hiện reuse** (`refreshToken()`): blacklist `jti` của RT bị replay (TC-RT-08).

TTL của key blacklist = `remainingTimeOf(RT)` (thời gian sống còn lại của chính RT đó).

---

#### Sub-case 4a: RT bị thu hồi qua LOGOUT (đường chính)

**Điều kiện tiên quyết:**
1. POST `/login` → nhận `RT_1` (copy giá trị cookie ngay), `sid`, `AT_1`.
2. **Chưa** refresh lần nào (để `jti_1 == refreshJtiCurrent`). Kiểm tra: `redis-cli HGET session:<sid> refreshJtiCurrent` = `jti_1` (decode `RT_1` trên jwt.io để lấy `jti`).
3. POST `/logout` với `Authorization: Bearer <AT_1>` + cookie `refreshToken=<RT_1>` (TC-LO-01).
4. Xác nhận đã blacklist: `redis-cli KEYS "blacklist:refresh:*"` có key `blacklist:refresh:<jti_1>`; đồng thời `redis-cli EXISTS session:<sid>` = `0` (logout đã xóa session).

**Các bước thực hiện:**
1. POST `/refresh-token` với header `Cookie: refreshToken=<RT_1>` (không cần gửi AT).

**Kỳ vọng:**
- HTTP **401**, body `{ "status":401, "code":2010, "message":"Token has been revoked", "timestamp":... }`.
- Vào đúng nhánh `isRefreshTokenRevoked` — trả **2010**, **KHÔNG** phải `3012 SESSION_INACTIVE`, dù session đã bị xóa ở bước 4. Đây chính là bằng chứng blacklist được kiểm **trước** session.

**Điểm verify đặc biệt (thứ tự guard):** nếu bạn nhận `3012` thay vì `2010`, nghĩa là thứ tự kiểm tra đã sai (session check chạy trước blacklist) — đó là bug.

---

#### Sub-case 4b: RT cũ bị thu hồi sau khi REFRESH (đường xoay token)

**Điều kiện tiên quyết:**
1. POST `/login` → copy `RT_1`.
2. POST `/refresh-token` (RT_1) → 200, nhận `RT_2`; lúc này `RT_1.jti` bị blacklist còn session vẫn `ACTIVE`.

**Các bước:** POST `/refresh-token` với `Cookie: refreshToken=<RT_1>` (RT cũ).

**Kỳ vọng:** HTTP 401, `code = 2010`.

> **Phân biệt với TC-RT-08 (reuse 3013):** ở đây session vẫn `ACTIVE` nhưng RT_1 đã bị blacklist ở bước refresh → guard blacklist bắt trước → **2010**. Guard reuse (`jti != refreshJtiCurrent` → **3013**) chỉ chạm tới khi RT **chưa** nằm trong blacklist. Nói cách khác: replay RT cũ ngay sau 1 lần refresh sẽ ra **2010** (vì đã blacklist), còn 3013 xảy ra với RT cũ **chưa** bị blacklist (ví dụ RT bị bỏ qua giữa chuỗi, hoặc blacklist đã hết TTL).

---

#### Sub-case 4c: Ép blacklist thẳng bằng Redis (cô lập, không phụ thuộc logout/refresh)

**Mục tiêu:** Test riêng guard blacklist với **session vẫn ACTIVE và jti vẫn khớp** — loại trừ mọi yếu tố khác.

**Điều kiện tiên quyết:**
1. POST `/login` → copy `RT_1`, lấy `jti_1` (decode) và `sid`.
2. Xác nhận nền sạch: `redis-cli HGET session:<sid> status` = `ACTIVE`, `redis-cli HGET session:<sid> refreshJtiCurrent` = `jti_1`.
3. Ép blacklist thủ công:
   ```bash
   redis-cli SET blacklist:refresh:<jti_1> revoked EX 600
   ```

**Các bước:** POST `/refresh-token` với `Cookie: refreshToken=<RT_1>`.

**Kỳ vọng:** HTTP 401, `code = 2010`. Vì session `ACTIVE` và jti khớp, nếu **không** có guard blacklist thì request đã thành công (200) — việc nhận 2010 chứng minh guard blacklist hoạt động độc lập và đứng trước.

---

**Kỳ vọng chung cho cả 3 sub-case:**
- HTTP 401, `code = 2010`, `message = "Token has been revoked"`.
- **Không** cấp AT/RT mới, **không** ghi `Set-Cookie` mới.

> **⚠️ Điểm bất nhất cần lưu ý (đáng cân nhắc sửa):** nhánh `TOKEN_REVOKED` trong `refreshToken()` `throw` mà **KHÔNG** gọi `clearRefreshTokenCookie(response)` — khác với hầu hết các nhánh lỗi khác (3009/3012/3013 đều xóa cookie). Hệ quả: cookie `refreshToken` chết vẫn còn trong browser sau khi nhận 2010, khiến client cứ gửi lại RT chết ở các request sau. Không phải lỗ hổng bảo mật (server vẫn chặn), nhưng nên bổ sung `clearRefreshTokenCookie(response)` vào nhánh này cho nhất quán. Khi test, quan sát response của cả 3 sub-case **không** có header `Set-Cookie` xóa cookie — đó là hiện trạng hiện tại.

---

### TC-RT-05: Session không tồn tại → SESSION_INACTIVE 3012

**Điều kiện tiên quyết:** Login lấy `RT_1` + `sid`. `RT_1` chưa bị revoke.

**Các bước:**
1. `redis-cli DEL session:<sid>`.
2. POST `/refresh-token` với `Cookie: refreshToken=<RT_1>`.

**Kỳ vọng:** HTTP 401, `code = 3012`. Cookie bị xóa.

---

### TC-RT-06: Session status REVOKED → SESSION_INACTIVE 3012

**Các bước:**
1. `redis-cli HSET session:<sid> status REVOKED`.
2. POST `/refresh-token` với `RT_1`.

**Kỳ vọng:** HTTP 401, `code = 3012`. Cookie bị xóa.

---

### TC-RT-07: Mất field refreshJtiCurrent → SESSION_INACTIVE 3012

**Các bước:**
1. `redis-cli HDEL session:<sid> refreshJtiCurrent`.
2. POST `/refresh-token` với `RT_1`.

**Kỳ vọng:** HTTP 401, `code = 3012` — nhánh `currentRefreshJtiObj == null`. Cookie bị xóa.

---

### TC-RT-08: ⭐ TOKEN REUSE — dùng lại RT cũ đã xoay → TOKEN_REUSE_DETECTED 3013

**Mục tiêu:** Nhánh bảo mật quan trọng nhất. Dùng lại RT đã bị rotate phải bị phát hiện và **giết luôn session**.

**Điều kiện tiên quyết:** Login → **copy `RT_1`** (giá trị cookie ngay sau login).

**Các bước:**
1. POST `/refresh-token` lần 1 (dùng `RT_1`) → 200, nhận `RT_2`. Lúc này `session.refreshJtiCurrent = jti_2`.
2. POST `/refresh-token` lần 2 nhưng **cố tình gửi lại `RT_1` cũ**: header `Cookie: refreshToken=<RT_1>`.

**Kỳ vọng:**
- HTTP 401, `code = 3013` (Token reuse detected).
- Redis: `session:{sid}.status` chuyển thành **REVOKED**.
- `blacklist:refresh:{jti_1}` được tạo (revoke RT bị tái sử dụng).
- Cookie bị xóa.

**Điểm verify đặc biệt:** sau bước 2, `HGET session:<sid> status` = `REVOKED`. Đây là cơ chế "một RT cũ bị replay ⇒ vô hiệu hóa toàn bộ phiên".

---

### TC-RT-09: User bị ban → ACCOUNT_BANNED 2007

**Điều kiện tiên quyết:** Login lấy `RT_1`, session ACTIVE, jti khớp.

**Các bước:**
1. DB: `UPDATE users SET is_deleted=true WHERE email='alice@example.com';`.
2. POST `/refresh-token` với `RT_1`.

**Kỳ vọng:** HTTP 403, `code = 2007`. Cookie bị xóa. (Dọn dẹp `is_deleted=false`.)

---

### TC-RT-10: User inactive → USER_INACTIVE 2005

**Các bước:**
1. DB: `UPDATE users SET is_active=false WHERE email='alice@example.com';`.
2. POST `/refresh-token` với `RT_1` (session ACTIVE, jti khớp).

**Kỳ vọng:** HTTP 403, `code = 2005`. Cookie bị xóa. (Dọn dẹp `is_active=true`.)

---

### TC-RT-11: User không tồn tại → USER_NOT_FOUND 2001 (biên)

**Mục tiêu:** Chạm nhánh `userRepository.findByUsernameWithRoles(...).orElseThrow`.

**Điều kiện tiên quyết:** Login lấy `RT_1` (sub = `alice01`), session ACTIVE, jti khớp.

**Các bước:**
1. DB: đổi username để không còn khớp sub: `UPDATE users SET username='alice_renamed' WHERE email='alice@example.com';`.
2. POST `/refresh-token` với `RT_1`.

**Kỳ vọng:** HTTP 404, `code = 2001` (User not found). (Dọn dẹp: đổi username về `alice01`.)

---

## Nhóm 4: LOGOUT — thu hồi token

`/logout` đọc RT từ cookie và AT từ header `Authorization` (nếu có), rồi: xóa session, gỡ session khỏi user, blacklist cả AT lẫn RT, luôn xóa cookie (idempotent).

### TC-LO-01: Logout đầy đủ (AT + RT) → 200, thu hồi cả 2 token

**Điều kiện tiên quyết:** Login → có `AT` (body) và `RT` (cookie) + `sid`.

**Các bước:**
1. POST `/logout` với:
   - Header `Authorization: Bearer <AT>`
   - Cookie `refreshToken=<RT>` (Postman tự gửi).

**Kỳ vọng:**
- HTTP 200 `{ "code":1000, "message":"Success" }`.
- Header `Set-Cookie` xóa `refreshToken` (Max-Age=0).
- Redis: `session:{sid}` **bị xóa**; `sid` bị gỡ khỏi `user:sessions:{userId}`.
- `blacklist:access:{jti_AT}` và `blacklist:refresh:{jti_RT}` được tạo (TTL = thời gian còn lại của mỗi token).

**Nối tiếp:** AT này giờ dùng sẽ ra 2010 (TC-AT-06); RT này dùng ra 2010 (TC-RT-04).

---

### TC-LO-02: Logout không cookie → 200 idempotent

**Các bước:** POST `/logout` **không** cookie, **không** header Authorization.

**Kỳ vọng:** HTTP 200 (không lỗi). Chỉ ghi `Set-Cookie` xóa `refreshToken`. Không đụng Redis (return sớm vì `refreshToken` null).

---

### TC-LO-03: Logout chỉ có RT (không gửi AT) → RT bị thu hồi, AT tự hết hạn

**Mục tiêu:** Xác nhận thiếu AT vẫn logout được; chỉ RT vào blacklist.

**Các bước:** POST `/logout` với cookie `refreshToken=<RT>` nhưng **không** header Authorization.

**Kỳ vọng:**
- HTTP 200, session xóa, `blacklist:refresh:{jti_RT}` được tạo.
- **Không** có `blacklist:access:*` cho phiên này (vì không gửi AT) → AT cũ vẫn hợp lệ đến khi hết hạn **NHƯNG** sẽ chết ngay vì session đã bị xóa → dùng AT cũ ra `3012 SESSION_INACTIVE` (không phải 2010).

**Điểm verify đặc biệt:** đây là lý do nên đăng xuất kèm AT (TC-LO-01) để thu hồi tức thì; nếu không, AT bị chặn gián tiếp qua việc session biến mất.

---

## Nhóm 5: Kịch bản kết hợp (E2E)

### TC-E2E-01: Vòng đời đầy đủ — login → dùng AT → refresh → dùng AT mới → logout → mọi thứ chết

**Các bước:**
1. POST `/login` → `AT_1`, `RT_1`, `sid`.
2. GET `/ping` với `AT_1` → 200 (TC-AT-01).
3. POST `/refresh-token` (RT_1) → `AT_2`, `RT_2`; `RT_1` bị blacklist.
4. GET `/ping` với `AT_2` → 200.
5. POST `/logout` với `Authorization: Bearer <AT_2>` + cookie `RT_2`.
6. Kiểm tra:
   - GET `/ping` với `AT_2` → **2010** (revoked).
   - POST `/refresh-token` với `RT_2` → **2010** (revoked).
   - POST `/refresh-token` với `RT_1` (cũ) → **2010** (đã revoke từ bước 3).

**Kỳ vọng:** Sau logout, không token nào của phiên còn dùng được. `session:{sid}` bị xóa.

---

### TC-E2E-02: Chuỗi xoay RT liên tiếp — mỗi RT mới sống, RT cũ chết

**Mục tiêu:** Xác nhận rotation liền mạch và mỗi lần refresh chỉ có đúng 1 RT hợp lệ.

**Các bước:**
1. Login → `RT_1`. Copy lại.
2. Refresh (RT_1) → `RT_2`. Copy lại.
3. Refresh (RT_2) → `RT_3`. Copy lại.
4. Kiểm tra: refresh với `RT_2` (đã xoay ở bước 3) → **3013 reuse** → `session.status = REVOKED`.
5. Sau bước 4, refresh với `RT_3` (đang là hiện hành) → **3012 SESSION_INACTIVE** (vì session đã bị reuse-detection giết ở bước 4).

**Kỳ vọng:** Chuỗi 1→2→3 mượt; replay bất kỳ RT cũ nào → 3013 và **giết cả phiên**, khiến RT hiện hành cũng chết theo.

**Điểm verify đặc biệt:** bước 5 chứng minh tính chất bảo mật: reuse-detection không chỉ chặn token cũ mà **vô hiệu hóa toàn bộ session** (buộc đăng nhập lại).

---

### TC-E2E-03: Đa thiết bị — logout thiết bị này không ảnh hưởng thiết bị kia

**Mục tiêu:** `deviceId` khác nhau tạo 2 session độc lập.

**Các bước:**
1. Login lần 1 `deviceId=11111111-...`, `deviceName=Laptop` → `sid_A`, `AT_A`, `RT_A`.
2. Login lần 2 `deviceId=22222222-2222-2222-2222-222222222222`, `deviceName=Phone` → `sid_B`, `AT_B`, `RT_B`.
3. Kiểm tra Redis: `SMEMBERS user:sessions:{userId}` chứa cả `sid_A` và `sid_B`.
4. Logout thiết bị A (Bearer `AT_A` + cookie `RT_A`).
5. Kiểm tra:
   - GET `/ping` với `AT_A` → 2010/3012 (chết).
   - GET `/ping` với `AT_B` → **200** (còn sống).
   - Refresh với `RT_B` → **200** (còn sống).

**Kỳ vọng:** Chỉ session A bị thu hồi; session B hoạt động bình thường. `user:sessions:{userId}` chỉ còn `sid_B`.

---

### TC-E2E-04: AT cũ vẫn sống sau refresh (đến khi hết hạn) — ghi chú thiết kế

**Mục tiêu:** Làm rõ hành vi: refresh KHÔNG thu hồi AT cũ.

**Các bước:**
1. Login → `AT_1`. (Nên đặt `ACCESS_TOKEN_LIFETIME` vừa phải, ví dụ 60000ms để quan sát.)
2. Refresh → `AT_2`, `RT_2`.
3. GET `/ping` với `AT_1` (cũ) ngay lập tức.

**Kỳ vọng:** `AT_1` **vẫn 200** (chưa hết hạn, không bị blacklist bởi refresh). Chỉ hết hiệu lực khi: (a) AT_1 hết hạn tự nhiên, hoặc (b) logout, hoặc (c) session bị xóa/REVOKED.

> Nếu coi đây là rủi ro, cân nhắc cho `/refresh-token` blacklist luôn AT cũ — nhưng cần AT cũ đi kèm request (hiện refresh không nhận AT). Ghi nhận như một quyết định kiến trúc.

---

### TC-E2E-05: TTL tuyệt đối 7 ngày của session (fixed window)

**Mục tiêu:** Session không gia hạn khi refresh; hết 7 ngày kể từ login là chết.

**Các bước (mô phỏng nhanh bằng Redis):**
1. Login → `sid`. `redis-cli TTL session:<sid>` ≈ `604800`.
2. Refresh vài lần → `redis-cli TTL session:<sid>` **vẫn giảm dần**, KHÔNG nhảy về 604800 (fixed window, không gia hạn).
3. Mô phỏng hết hạn: `redis-cli EXPIRE session:<sid> 1`, chờ 2s.
4. Refresh với RT hiện hành → **3012 SESSION_INACTIVE** (session đã hết TTL dù RT trong JWT còn hạn).

**Kỳ vọng:** RT của JWT có thể còn hạn (7 ngày) nhưng khi `session:{sid}` hết TTL, refresh vẫn bị chặn. Xác nhận session là nguồn sự thật, không chỉ dựa chữ ký JWT.

---

## Phụ lục A — Bảng tra ErrorCode
| code | tên | HTTP | Xuất hiện ở |
|---|---|---|---|
| 2001 | USER_NOT_FOUND | 404 | refresh (RT-11) |
| 2005 | USER_INACTIVE | 403 | filter (AT-12) · refresh (RT-10) |
| 2007 | ACCOUNT_BANNED | 403 | filter (AT-11) · refresh (RT-09) |
| 2010 | TOKEN_REVOKED | 401 | filter (AT-06) · refresh (RT-04) |
| 3001 | UNAUTHENTICATED | 401 | filter (AT-04, AT-09) |
| 3009 | UNAUTHORIZED | 401 | refresh (RT-02, RT-03), token invalid |
| 3012 | SESSION_INACTIVE | 401 | filter (AT-07, AT-08) · refresh (RT-05, RT-06, RT-07) · E2E |
| 3013 | TOKEN_REUSE_DETECTED | 401 | refresh (RT-08, E2E-02) ⭐ |
| 3015 | ACCESS_TOKEN_EXPIRED | 401 | filter (AT-05) |
| 3016 | SESSION_DEVICE_MISMATCH | 401 | filter (AT-10) |
| 4001 | LOGIN_SUCCESS (SuccessCode) | 200 | login (LOGIN-01) · refresh (RT-01) |
| — | fallback (không code) | 401 | filter khi không gửi token (AT-02, AT-03) |

## Phụ lục B — Checklist xác minh sau mỗi TC
- [ ] HTTP status đúng (200 khi thành công; 401/403/404 khi lỗi theo bảng trên).
- [ ] `code` đúng: thành công đọc `data.code` (4001); lỗi đọc `code` top-level (hoặc vắng `code` ở nhánh fallback).
- [ ] Cookie `refreshToken`: **set mới** khi login & refresh thành công (rotation); **xóa** (Max-Age=0) khi logout / lỗi refresh; **không đổi** với các lỗi tại filter.
- [ ] `Set-Cookie` giữ đủ thuộc tính: HttpOnly, Secure, SameSite=Strict.
- [ ] Redis session: `status` đúng (`ACTIVE`/`REVOKED`); `refreshJtiCurrent` **đổi sau mỗi refresh**; `lastSeen` cập nhật khi qua filter/refresh; session **bị xóa** sau logout.
- [ ] Blacklist: `blacklist:refresh:{jti}` tạo ở refresh (RT cũ) & logout; `blacklist:access:{jti}` chỉ tạo ở logout có gửi AT.
- [ ] `user:sessions:{userId}` phản ánh đúng số phiên còn sống.
- [ ] Reuse (RT-08/E2E-02): sau khi phát hiện, `session.status = REVOKED` và mọi token của phiên đều chết.
- [ ] Dọn dẹp DB sau các TC sửa `is_deleted`/`is_active`/`username`.
