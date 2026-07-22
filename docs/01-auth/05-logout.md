# Đăng xuất (Logout)

> Tài liệu cho **một endpoint**: `POST /api/v1/auth/logout` — thu hồi session hiện tại +
> blacklist token, thiết kế **idempotent** (gọi bao nhiêu lần cũng không lỗi) và **best-effort**
> (thiếu thông tin gì thì bỏ qua phần đó, không bao giờ ném exception).
>
> Bám sát code: `AuthServiceImplement.logout` + `RedisService.deleteSession` /
> `removeSessionFromUser` + `TokenBlacklistServiceImpl`. Cập nhật: 2026-07-05.
>
> 📎 Doc này giả định đã đọc `3. Kiến trúc Session & Token.md` (2 nhóm key Redis,
> `session:{}`/`user:sessions:{}`/`blacklist:*`) và `4. Refresh token.md` (guard chain của
> refresh — để hiểu vì sao logout "không cần hoàn hảo" vẫn đủ an toàn, xem §5).

## Mục lục
1. [Toàn cảnh — luôn trả 200](#1-toàn-cảnh--luôn-trả-200)
2. [Request](#2-request)
3. [Luồng xử lý từng bước](#3-luồng-xử-lý-từng-bước)
4. [Idempotency — vì sao không bao giờ lỗi](#4-idempotency--vì-sao-không-bao-giờ-lỗi)
5. [Điểm đặc biệt: blacklist "RT nào"?](#5-điểm-đặc-biệt-blacklist-rt-nào)
6. [Response](#6-response)
7. [Bảng tra mã](#7-bảng-tra-mã)
8. [Ghi chú & điểm dễ nhầm](#8-ghi-chú--điểm-dễ-nhầm)

---

## 1. Toàn cảnh — luôn trả 200

Khác với Login/Refresh (có thể ném `AppException` → HTTP 401/403/404...), **`logout()` không
bao giờ ném exception**. Endpoint này được thiết kế theo tinh thần: *"dọn được gì thì dọn, không
dọn được cũng không sao — chỉ cần đảm bảo phía client sạch sẽ (cookie bị xóa) và server không
crash"*.

```
logout(refreshToken?, request, response)
  │
  ├─ LUÔN xóa cookie refreshToken trước tiên (bất kể có hợp lệ hay không)
  │
  ├─ refreshToken null/blank?           → return (200, đã xóa cookie, hết việc)
  ├─ parse claim (RT) lỗi?              → return (200, đã xóa cookie, hết việc)
  ├─ session:{sessionId} không tồn tại? → return (200, đã xóa cookie, hết việc)
  │
  └─ mọi thứ có đủ:
        ├─ xóa session:{sessionId}
        ├─ gỡ sessionId khỏi user:sessions:{userId}  (best-effort)
        ├─ blacklist accessToken (nếu có gửi kèm)
        └─ blacklist refreshToken hiện hành của session
```

Vì vậy **HTTP response của logout luôn là `200 { code: 1000 }`**, dù request không có cookie,
cookie hỏng, hay session đã chết từ trước.

---

## 2. Request

| Input | Nguồn | Bắt buộc |
|---|---|---|
| `refreshToken` | Cookie `refreshToken` | không — thiếu thì logout vẫn "thành công" (chỉ dọn cookie) |
| `Authorization: Bearer <accessToken>` | Header | không — nếu có sẽ tranh thủ blacklist luôn accessToken hiện tại |

Không có request body.

---

## 3. Luồng xử lý từng bước

1. **Luôn xóa cookie `refreshToken`** (`clearRefreshTokenCookie`) — bước đầu tiên, không điều
   kiện. Đây là hành động duy nhất chắc chắn xảy ra mỗi lần gọi.
2. **`refreshToken` null/blank → return ngay.** Không có gì để thu hồi ở tầng server.
3. **Đọc header `Authorization`**, nếu có tiền tố `Bearer ` thì tách ra `accessToken` (chưa xử lý
   vội, chỉ giữ biến).
4. **Parse claim từ `refreshToken`**: `remainingTimeOf`, `extractUsername`, `extractSessionId`.
   Lỗi (token rác/hết hạn hẳn) → `catch (Exception) { return; }` — không xóa gì thêm, vì chưa
   biết chắc `sessionId`/`username` thật sự là gì.
5. **Kiểm tra `session:{sessionId}` có tồn tại không** (`hasKey`). Không tồn tại → return ngay
   (session đã chết từ trước, không có gì để xóa thêm).
6. **Đọc `refreshJtiCurrent`** từ session (có thể `null` nếu field bị thiếu — vẫn tiếp tục, chỉ
   là sẽ không blacklist được RT ở bước sau).
7. **Nếu có `accessToken`**: thử `extractJti` + `remainingTimeOf`; lỗi thì `catch (ignored)` —
   AT hỏng/hết hạn thì bỏ qua, **không** làm gián đoạn phần còn lại của logout.
8. **Xóa hẳn `session:{sessionId}`** (`deleteSession`) — khác với reuse-detection (chỉ đổi
   `status=REVOKED`, giữ lại hash), logout **xóa toàn bộ key**.
9. **Gỡ `sessionId` khỏi `user:sessions:{userId}`** — best-effort: `findByUsername(username)`
   trả rỗng thì **bỏ qua bước này**, không throw, để phần blacklist phía sau vẫn chạy.
10. **Blacklist `accessToken`** nếu đã lấy được `jti` + `remaining > 0` ở bước 7.
11. **Blacklist refresh token hiện hành của session** — dùng `currentRefreshJti` đọc từ session ở
    bước 6 (**không phải** `jti` trích từ chính `refreshToken` trong request — xem lý do ở §5),
    với TTL = `remainingTimeOf(refreshToken)` tính từ token trong request.

---

## 4. Idempotency — vì sao không bao giờ lỗi

Gọi `/logout` nhiều lần liên tiếp (hoặc gọi lại sau khi đã logout) đều an toàn:

- **Lần đầu:** cookie hợp lệ → chạy hết pipeline → session bị xóa, token bị blacklist, cookie bị
  xóa ở response.
- **Lần hai** (nếu client vẫn cố gửi lại `refreshToken` cũ — ví dụ tab khác chưa kịp nhận cookie
  mới): parse claim vẫn thành công (token tự nó chưa hết hạn), nhưng bước 5 (`hasKey`) trả
  `false` vì session đã bị xóa ở lần đầu → **return sớm**, vẫn `200`, không có gì để làm thêm.
- **Không có cookie** (client đã xóa cookie từ trước, hoặc gọi từ Postman không set cookie):
  dừng ở bước 2, `200`.

Không có trạng thái nào trong pipeline này có thể khiến logout trả lỗi cho client — **mọi
nhánh dừng sớm đều kết thúc bằng `return` (thành công)**, chưa từng ném `AppException`.

---

## 5. Điểm đặc biệt: blacklist "RT nào"?

Điểm dễ bị đọc lướt qua: RT bị đưa vào blacklist ở bước 11 **không phải** `jti` trích trực tiếp từ
`refreshToken` mà client gửi lên, mà là **`refreshJtiCurrent` đang lưu trong session** (bước 6).

Trong đại đa số trường hợp hai giá trị này **giống nhau** (client gửi đúng RT mới nhất, session
cũng đang trỏ tới đúng RT đó). Nhưng về mặt thiết kế, đây là lựa chọn có chủ đích: **logout luôn
thu hồi "bản RT mà hệ thống đang công nhận là hợp lệ cho phiên này"**, bất kể RT cụ thể nào được
đính kèm trong cookie của request logout — miễn cookie đó parse được `sessionId` trỏ đúng tới
session còn tồn tại.

Vì sao không quan trọng RT nào được blacklist? Vì **bước 8 đã xóa hẳn session** — bất kỳ RT nào
(cũ hay mới) của phiên này khi đem đi `refresh-token` sau đó đều dừng lại ở guard "session không
tồn tại" (`3012 SESSION_INACTIVE`, xem `4. Refresh token.md` §3 bước 4), **không cần** tới
blacklist mới chặn được. Blacklist ở bước 11 chỉ là lớp phòng thủ bổ sung để RT bị chặn ngay từ
guard sớm hơn (`2010 TOKEN_REVOKED`, bước 3 trong guard chain refresh) thay vì phải đi tới tận
guard session.

> **Hệ quả nếu `remainingTimeOf(refreshToken)` ≤ 0** (RT trong cookie đã hết hạn tự nhiên tại
> thời điểm logout): điều kiện `remainingTTLRefreshToken > 0` ở bước 11 sai → **không** tạo entry
> blacklist. Không sao — token đó đã hết hạn thật, blacklist thêm cũng vô nghĩa; và session vẫn
> đã bị xóa ở bước 8.

---

## 6. Response

Luôn là **HTTP 200**, không phụ thuộc input:

```jsonc
{ "code": 1000, "message": "Success" }
```

(`APIResponse.success()` không có `data` — khác Login/Refresh vốn trả `AuthResponse` trong
`data`.)

Kèm header `Set-Cookie: refreshToken=; Max-Age=0; ...` để trình duyệt xóa cookie ngay.

---

## 7. Bảng tra mã

Logout **không** có `ErrorCode` nào có thể ném ra — không giống mọi endpoint khác trong nhóm
Auth. `SuccessCode` cũng không dùng riêng, response chỉ có `code: 1000` (envelope) và không có
`data.code` nghiệp vụ.

| Tình huống | HTTP | Cookie bị xóa? | Session bị xóa? | Token bị blacklist? |
|---|:--:|:--:|:--:|:--:|
| Đủ AT + RT hợp lệ, session tồn tại | 200 | ✓ | ✓ | AT ✓, RT ✓ |
| Chỉ RT hợp lệ (không gửi AT) | 200 | ✓ | ✓ | AT ✗ (không có để blacklist), RT ✓ |
| Không có cookie RT | 200 | ✓ (ghi đè bằng cookie rỗng) | ✗ | ✗ |
| RT rác/hết hạn không parse được | 200 | ✓ | ✗ | ✗ |
| RT hợp lệ nhưng session đã chết từ trước | 200 | ✓ | ✗ (đã chết) | ✗ |

---

## 8. Ghi chú & điểm dễ nhầm

- **Nên luôn gửi kèm `Authorization: Bearer <accessToken>` khi gọi logout** — nếu không, AT hiện
  tại **không** được đưa vào `blacklist:access:*` ngay lập tức. Nó vẫn "chết" gián tiếp (vì
  session đã bị xóa → `JwtAuthFilter` sẽ trả `3012 SESSION_INACTIVE` khi AT đó được dùng lại),
  nhưng chậm hơn 1 bước so với việc bị chặn thẳng bởi `2010 TOKEN_REVOKED`. Về mặt bảo mật kết
  quả cuối như nhau (AT không dùng được), chỉ khác mã lỗi trả về.
- **Logout không kiểm tra `user.isDeleted()`/`user.isActive()`** — không cần, vì mục đích chỉ là
  "dọn session", không phải "xác thực để cấp quyền". Tài khoản bị ban vẫn logout được bình
  thường.
- **`removeSessionFromUser` là best-effort**: nếu `findByUsername(username)` không ra kết quả
  (user bị xóa cứng khỏi DB giữa chừng), bước này bị bỏ qua **âm thầm**, không throw, để 2 bước
  blacklist phía sau vẫn chạy tiếp. `user:sessions:{userId}` có thể còn sót `sessionId` rác trong
  trường hợp hiếm này (session bản thân nó đã bị xóa nên không ảnh hưởng chức năng, chỉ là dữ
  liệu thống kê "danh sách thiết bị" — nếu sau này xây — sẽ hơi lệch).
- **Logout hiện chỉ thu hồi ĐÚNG 1 session** (phiên ứng với `sessionId` trong RT được gửi lên) —
  không phải "đăng xuất tất cả thiết bị". Hạ tầng cho tính năng đó đã có sẵn
  (`RedisService.getUserSessions` / `deleteAllUserSessionsIndex`, xem doc 3 §3) nhưng **chưa có
  endpoint** gọi tới.
- **Không có khái niệm "logout thất bại" ở tầng HTTP.** Nếu cần biết "logout có thực sự thu hồi
  được gì hay không" (ví dụ để hiển thị log/audit), phải quan sát Redis trực tiếp — response API
  không phân biệt được "đã xóa session thật" với "chẳng có gì để xóa".

---

*Đây là chức năng cuối cùng khép lại vòng đời token trong nhóm Auth cốt lõi (Register/OTP → Login
→ Refresh → Logout). Chức năng tiếp theo (nếu có): `6. JwtAuthFilter.md` — mô tả chi tiết bộ lọc
xác thực áp dụng cho mọi endpoint protected khác trong hệ thống.*
