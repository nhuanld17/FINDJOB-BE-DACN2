# Refresh Token

> Tài liệu cho **một endpoint**: `POST /api/v1/auth/refresh-token` — cấp lại `accessToken` bằng
> `refreshToken`, kèm **rotation** (xoay RT mỗi lần refresh) và **reuse-detection** (phát hiện RT
> cũ bị dùng lại → giết cả session).
>
> Bám sát code: `AuthServiceImplement.refreshToken` + `JwtUtil` + `RedisService` +
> `TokenBlacklistServiceImpl`. Cập nhật: 2026-07-05 (đối chiếu lại dual-mode 2026-08-06).
>
> 📎 Doc này giả định đã đọc `3. Kiến trúc Session & Token.md` (claim JWT, 2 nhóm key Redis,
> triết lý "session là nguồn sự thật", TTL 7 ngày fixed-window). Ở đây chỉ tập trung vào **luồng
> xử lý của riêng endpoint refresh**.

## Mục lục
1. [Vị trí trong vòng đời token](#1-vị-trí-trong-vòng-đời-token)
2. [Request & cookie](#2-request--cookie)
3. [Guard chain — thứ tự kiểm tra](#3-guard-chain--thứ-tự-kiểm-tra)
4. [Rotation — cấp token mới](#4-rotation--cấp-token-mới)
5. [Reuse-detection — "Hướng B"](#5-reuse-detection--hướng-b)
6. [Response](#6-response)
7. [Bảng tra mã](#7-bảng-tra-mã)
8. [Ghi chú & điểm dễ nhầm](#8-ghi-chú--điểm-dễ-nhầm)

---

## 1. Vị trí trong vòng đời token

`accessToken` sống ngắn, hết hạn thì FE gọi `refresh-token` bằng `refreshToken` để lấy
`accessToken` mới **mà không bắt user đăng nhập lại** — miễn `refreshToken` còn hợp lệ và
**session đứng sau nó còn sống** (xem triết lý "session là nguồn sự thật" ở doc 3).

**Dual-mode gửi RT (từ `AuthController.refreshToken`):**
- **Web:** RT nằm trong cookie HttpOnly `refreshToken` — browser tự gửi kèm, không cần body.
- **Mobile:** RT nằm trong **body JSON** `{ "refreshToken": "..." }` (lưu secure store).
- Server ưu tiên body, không có body thì fallback cookie.

```
AT hết hạn (JwtAuthFilter trả 3015 ACCESS_TOKEN_EXPIRED)
        │
        ▼
FE gọi POST /refresh-token
   web    → cookie refreshToken tự động gửi kèm
   mobile → body { "refreshToken": "..." }
        │
        ├─ hợp lệ  → AT mới (body) + RT mới (cookie web / body mobile — rotation)
        └─ không hợp lệ → 401/403 tương ứng, FE điều hướng về Login
```

Endpoint này nằm trong `PUBLIC_PATTERNS` (`/api/v1/auth/**`) nên **không** đi qua `JwtAuthFilter`
— việc xác thực RT do chính `refreshToken()` tự làm từ đầu đến cuối.

---

## 2. Request & cookie

| Input | Nguồn | Bắt buộc |
|---|---|---|
| `refreshToken` | **Body** `{ "refreshToken": "..." }` (mobile) **hoặc** Cookie `refreshToken` (web) | không bắt buộc ở tầng HTTP (`@RequestBody(required=false)` + `@CookieValue(required=false)`), nhưng thiếu cả 2 thì luôn thất bại |

Request body (mobile):
```json
{ "refreshToken": "eyJhbGciOiJIUzUxMiJ9..." }
```

Mỗi lần refresh **thành công**, server **rotate**: ghi đè cookie `refreshToken` (web — cùng
thuộc tính như lúc login: `HttpOnly`, `Secure`, `SameSite=Strict`, `path=/`, `maxAge=7 ngày`)
**và/hoặc** trả RT mới trong body (mobile).

---

## 3. Guard chain — thứ tự kiểm tra

`refreshToken()` chạy qua một chuỗi guard **tuần tự**, sai ở bước nào dừng ở bước đó. Thứ tự này
quan trọng — xem §5 để hiểu vì sao thứ tự "blacklist trước reuse" lại là mấu chốt bảo mật.

```
1. refreshToken null/blank?              → xóa cookie, throw 3009 UNAUTHORIZED
2. parse claim (sub/jti/sessionId/deviceId) lỗi?
                                          → xóa cookie, throw 3009 UNAUTHORIZED
3. jti nằm trong blacklist:refresh:*?    → throw 2010 TOKEN_REVOKED         (⚠ không xóa cookie — xem §8)
4. session:{sessionId} không tồn tại?    → xóa cookie, throw 3012 SESSION_INACTIVE
5. session.status != ACTIVE?             → xóa cookie, throw 3012 SESSION_INACTIVE
6. session.currentRefreshJti rỗng/null?  → xóa cookie, throw 3012 SESSION_INACTIVE
7. jti (token) != currentRefreshJti?     → session.status=REVOKED + blacklist jti này
                                            + xóa cookie, throw 3013 TOKEN_REUSE_DETECTED  ⭐
8. tìm user theo username (từ sub)       → không thấy: throw 2001 USER_NOT_FOUND (không xóa cookie)
9. user.isDeleted()?                     → xóa cookie, throw 2007 ACCOUNT_BANNED
10. !user.isActive()?                    → xóa cookie, throw 2005 USER_INACTIVE
11. isTokenValid(token, userDetails) sai? → xóa cookie, throw 3009 UNAUTHORIZED
12. mọi guard qua → ROTATION (xem §4) → 200 + AT mới + RT mới
```

| Bước | Guard | Trả lời câu hỏi |
|:--:|---|---|
| 1–2 | Cookie & chữ ký | "Đây có phải một JWT hợp lệ do server này ký không?" |
| 3 | Blacklist | "Token này đã bị **chủ động thu hồi** (qua logout) chưa?" |
| 4–6 | Session tồn tại & active | "Phiên đăng nhập này còn sống trong Redis không?" |
| 7 | So khớp `jti` | "Đây có phải bản RT **mới nhất** của phiên, hay là bản đã bị thay thế?" |
| 8–10 | User ở tầng DB | "User đứng sau session này còn dùng được không (chưa bị ban/deactivate)?" |
| 11 | `isTokenValid` | double-check `sub` khớp + chưa hết hạn (gần như luôn qua vì đã qua bước 1–2) |

---

## 4. Rotation — cấp token mới

Khi qua hết guard chain (RT hợp lệ, đúng phiên hiện hành, user còn dùng được):

1. **Cấp `accessToken` mới** — `jwtUtil.generateAccessToken(userDetails, sessionId, deviceId)`,
   **giữ nguyên** `sessionId`/`deviceId` đọc từ RT cũ.
2. **Cấp `refreshToken` mới** (rotation) — `jwtUtil.generateRefreshToken(...)`, cùng `sessionId`/
   `deviceId`, nhưng `jti` mới và `exp` mới (+7 ngày kể từ **thời điểm refresh**, không phải kể
   từ login — khác với TTL của `session:{sessionId}` trong Redis, xem lưu ý ở §8 và ở doc 3 §6).
3. **Cập nhật `session:{sessionId}.currentRefreshJti`** = `jti` của RT **mới** — đây là bước biến
   RT cũ thành "không còn là bản hiện hành" nữa (nền tảng của reuse-detection, xem §5).
4. **Cập nhật `session:{sessionId}.lastSeen`** = thời điểm hiện tại.
5. **Ghi đè cookie `refreshToken`** bằng RT mới (web).
6. Trả `AuthResponse` — accessToken mới + **`refreshToken` mới trong body (mobile)**; web: `refreshToken = null` (chỉ cookie).

> **Không sinh `sessionId` mới khi refresh** — session vẫn là cùng một phiên xuyên suốt vòng đời
> 7 ngày, chỉ có "credential" (RT/AT) được thay liên tục bên trong nó.

---

## 5. Reuse-detection — "Hướng B"

### Cơ chế

Chống-reuse **hoàn toàn dựa vào việc so khớp `jti`** giữa RT gửi lên và
`session.currentRefreshJti`:

- RT nào có `jti` **khớp** field này → đang là bản hợp lệ hiện hành → cho refresh, rồi ghi đè
  field bằng `jti` của RT mới (bước 3 ở §4).
- RT nào có `jti` **không khớp** → đây là một RT **đã từng hợp lệ nhưng đã bị thay thế** (tức là
  đã được rotate ít nhất 1 lần) đang bị gửi lại → **coi như bị lộ/bị replay** →
  `session.status = REVOKED` + blacklist chính `jti` đó + `3013 TOKEN_REUSE_DETECTED` → **toàn
  bộ session chết**, mọi RT/AT khác của phiên này (kể cả bản đang "hiện hành") cũng vô dụng theo
  vì session không còn `ACTIVE`.

Đây là mô hình **refresh token family**: cả chuỗi RT sinh ra từ một lần login thuộc "cùng một gia
đình", chỉ có đúng 1 thành viên "còn sống" tại một thời điểm; hễ phát hiện một thành viên đã-chết
bị dùng lại, cả gia đình bị khai tử — buộc login lại. Đây là phản ứng đúng theo mô hình đe dọa
"kẻ tấn công đánh cắp được RT nhưng nạn nhân vẫn đang dùng app": người dùng thật refresh trước,
rồi RT bị đánh cắp (đã cũ) mới được kẻ tấn công dùng → giật mình → giết cả phiên → cả hai bên đều
bị đăng xuất, nhưng kẻ tấn công **không** chiếm được phiên.

### Vì sao KHÔNG blacklist RT cũ ngay sau khi rotate

Đây là điểm đã được **sửa lại có chủ đích** ("Hướng B"), khác với một cách làm tưởng chừng hợp lý
hơn ("Hướng A": cứ rotate xong là blacklist luôn RT vừa bị thay thế). Vấn đề của Hướng A nằm ở
**thứ tự guard chain** (§3): bước 3 — kiểm tra blacklist — luôn chạy **trước** bước 7 — so khớp
`jti`. Nếu RT cũ bị blacklist ngay khi rotate:

```
Hướng A (đã bỏ):
  refresh bằng RT cũ (đã bị thay thế + đã bị blacklist)
        │
        ▼
  bước 3: nằm trong blacklist? → CÓ → throw 2010 TOKEN_REVOKED   ← dừng tại đây
        │
        ✗ không bao giờ chạm tới bước 7 (so khớp jti / reuse-detection)
        ✗ session vẫn "ACTIVE" — không có gì bị vô hiệu hóa
```

Tức là **Hướng A khiến nhánh reuse-detection (`3013` + giết session) không bao giờ được kích
hoạt** — mọi trường hợp replay RT cũ chỉ dừng ở `2010`, và quan trọng nhất: **session của nạn
nhân vẫn sống bình thường**, không có tín hiệu cảnh báo "có RT bị lộ" nào được xử lý ở tầng
session. Tính năng "phát hiện lộ token ⇒ hủy cả family" coi như vô hiệu trên thực tế dù code có
viết ra.

**Hướng B** (đang áp dụng): refresh **không chủ động blacklist RT vừa bị thay thế**. RT cũ vẫn
"chết" — nhưng chết vì lý do khác: `jti` của nó không còn khớp `currentRefreshJti`. Nếu ai đó
(kẻ tấn công hoặc chính client do bug) gửi lại RT cũ, request sẽ **đi thẳng qua được bước 3**
(chưa bị blacklist) và **dừng đúng ở bước 7** — nơi nhánh reuse-detection thực sự được kích hoạt:
session bị `REVOKED`, RT đó **mới** bị blacklist tại đây, trả `3013`.

```
Hướng B (đang dùng):
  refresh bằng RT cũ (đã bị thay thế, CHƯA bị blacklist)
        │
        ▼
  bước 3: nằm trong blacklist? → KHÔNG → đi tiếp
        │
        ▼
  bước 7: jti khớp currentRefreshJti? → KHÔNG khớp
        │
        ▼
  session.status = REVOKED, blacklist jti này, throw 3013 TOKEN_REUSE_DETECTED
        ✓ session bị giết → cả family (mọi RT/AT khác của phiên) cũng chết theo
```

Nói ngắn gọn: **blacklist `2010` dành cho thu hồi chủ động (logout)**, còn **`3013` dành riêng
cho phát hiện replay** — hai cơ chế không được phép giẫm chân nhau, và thứ tự guard (blacklist
check đứng trước reuse check) chỉ an toàn khi **refresh không tự ý ghi vào blacklist**.

---

## 6. Response

**Thành công (HTTP 200):**

```jsonc
{
  "code": 1000, "message": "Success",
  "data": {
    "code": 4001,              // SuccessCode.LOGIN_SUCCESS — TÁI DÙNG, không có mã riêng cho refresh
    "id": 123,
    "username": "alice01",
    "roles": [ ... ],
    "accessToken": "eyJhbGci...",   // access token MỚI
    "refreshToken": "eyJhbGci...",  // MỚI: có giá trị với mobile (body), null với web (chỉ cookie)
    "hasPassword": true              // MỚI: user đã có mật khẩu cũ chưa
  }
}
```

> **Dual-mode:** web nhận RT mới qua `Set-Cookie`; mobile nhận `data.refreshToken` trong body — giống quy ước ở Login.

**Lỗi:** đi qua `GlobalExceptionHandler`, trả `ErrorResponse` chuẩn:

```jsonc
{ "status": 401, "code": 3013, "message": "Token reuse detected", "timestamp": "..." }
```

---

## 7. Bảng tra mã

### ErrorCode có thể ném (qua `AppException` → `ErrorResponse`)

| Code | Tên | HTTP | Khi nào | Xóa cookie? |
|:--:|---|:--:|---|:--:|
| 3009 | UNAUTHORIZED | 401 | thiếu cookie / parse claim lỗi / `isTokenValid` sai | ✓ |
| 2010 | TOKEN_REVOKED | 401 | `jti` nằm trong `blacklist:refresh:*` (thường do đã logout) | ✗ (xem §8) |
| 3012 | SESSION_INACTIVE | 401 | session không tồn tại / không `ACTIVE` / thiếu `currentRefreshJti` | ✓ |
| 3013 | TOKEN_REUSE_DETECTED | 401 | `jti` không khớp `currentRefreshJti` — replay RT cũ ⭐ | ✓ |
| 2001 | USER_NOT_FOUND | 404 | user biến mất khỏi DB giữa chừng | ✗ (xem §8) |
| 2007 | ACCOUNT_BANNED | 403 | `user.isDeleted()=true` | ✓ |
| 2005 | USER_INACTIVE | 403 | `user.isActive()=false` | ✓ |

### SuccessCode

| Code | Tên | Ghi chú |
|:--:|---|---|
| 4001 | LOGIN_SUCCESS | dùng chung với login — refresh **không có** mã riêng |

---

## 8. Ghi chú & điểm dễ nhầm

- **Nhánh `2010 TOKEN_REVOKED` không xóa cookie `refreshToken`** — khác với hầu hết nhánh lỗi
  khác trong endpoint này (3009/3012/3013 đều gọi `clearRefreshTokenCookie`). Hệ quả: cookie chết
  vẫn còn ở trình duyệt, client sẽ tiếp tục gửi lại RT đã revoke ở các lần sau (vẫn bị chặn đúng,
  không phải lỗ hổng bảo mật, nhưng nên bổ sung cho nhất quán).
- **Nhánh `2001 USER_NOT_FOUND` cũng không xóa cookie** (dùng `orElseThrow` trực tiếp, không nằm
  trong khối có gọi `clearRefreshTokenCookie`). Trường hợp này hiếm gặp trong thực tế (user bị
  xóa cứng khỏi DB khi vẫn còn RT sống).
- **Rotation không tạo `sessionId` mới** — chỉ tạo `jti` mới cho AT/RT và cập nhật
  `currentRefreshJti`. `session:{sessionId}` là **cùng một bản ghi Redis** xuyên suốt vòng đời
  7 ngày, TTL của nó **không được gia hạn** dù refresh liên tục (xem doc 3 §6) — refresh token
  mới có `exp` = +7 ngày kể từ lúc refresh, nhưng session vẫn chết đúng 7 ngày kể từ **login
  gốc**. Nghĩa là refresh gần cuối chu kỳ vẫn có thể cấp một RT có `exp` "hứa hẹn" dài hơn thời
  gian session thực sự còn sống.
- **`isTokenValid` ở bước 11 gần như luôn `true`** khi đến được bước này — vì token đã được parse
  claim thành công ở bước 2 (nên chưa hết hạn tại thời điểm đó) và `sub` chắc chắn khớp (vừa dùng
  chính `sub` đó để load `userDetails`). Guard này chủ yếu là lớp phòng thủ kép, không phải guard
  hay bị chạm trong thực tế.
- **Access token cũ (trước khi refresh) không bị vô hiệu hóa bởi chính hành động refresh.** AT cũ
  vẫn dùng được cho tới khi tự hết hạn, hoặc tới khi logout, hoặc tới khi session chết vì lý do
  khác. Đây là quyết định kiến trúc đã ghi nhận (không phải bug) — muốn AT cũ chết ngay khi
  refresh thì endpoint cần nhận thêm AT cũ trong request để blacklist, hiện chưa làm.
- **`currentRefreshJti` là "con dấu" duy nhất xác định bản RT hiện hành** — không dựa vào việc RT
  có nằm trong blacklist hay không để quyết định "còn hiệu lực". Hai khái niệm "bị thu hồi"
  (blacklist) và "không còn là bản hiện hành" (reuse) là **độc lập**, cố tình không gộp làm một
  (xem §5).

---

*Chức năng tiếp theo: `5. Logout.md` (thu hồi session + blacklist, idempotent).*
