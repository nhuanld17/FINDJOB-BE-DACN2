# Đăng ký & OTP

> Tài liệu cho **một chức năng**: đăng ký tài khoản và kích hoạt bằng OTP.
> Gồm 3 endpoint hoạt động cùng nhau: `register` → (`verify-otp` \| `resend-otp`)* → tài khoản `ACTIVE`.
>
> Bám sát code: `AuthServiceImplement` (register/verifyOtp/resendOtp) + `OtpServiceImpl` +
> `RegisterRequest`/`VerifyOtpRequest` + `SuccessCode`/`ErrorCode`. Cập nhật: 2026-07-05.

## Mục lục
1. [Toàn cảnh luồng](#1-toàn-cảnh-luồng)
2. [Bốn "đồng hồ" OTP + pending token (khái niệm nền)](#2-bốn-đồng-hồ-otp--pending-token)
3. [Register](#3-register)
4. [Verify OTP](#4-verify-otp)
5. [Resend OTP](#5-resend-otp)
6. [Bảng tra mã](#6-bảng-tra-mã)
7. [Ghi chú & điểm dễ nhầm](#7-ghi-chú--điểm-dễ-nhầm)

---

## 1. Toàn cảnh luồng

```
                      ┌──────────────┐
   người dùng  ─────► │  register    │  tạo/cập nhật user (isActive=false)
                      └──────┬───────┘  + phát OTP qua email, set cookie pendingToken
                             │
                             ▼
                      ┌──────────────┐   nhập sai / hết OTP
                      │  verify-otp  │◄──────────────┐
                      └──────┬───────┘               │
                    đúng OTP │                        │ bấm "Gửi lại mã"
                             ▼                        │
                      ┌──────────────┐         ┌──────┴───────┐
                      │ ACTIVE=true  │         │  resend-otp  │
                      │ → đi Login   │         └──────────────┘
                      └──────────────┘
```

3 endpoint (đều `POST`, đều bọc response trong `APIResponse`):

| Endpoint | Việc | Đầu vào |
|---|---|---|
| `/api/v1/auth/register` | tạo/cập nhật tài khoản chưa kích hoạt + quyết định có phát OTP mới không | body `RegisterRequest` + cookie `pendingToken` (tùy chọn) |
| `/api/v1/auth/verify-otp` | nhập OTP để kích hoạt | body `otp` + cookie `pendingToken` (bắt buộc) |
| `/api/v1/auth/resend-otp` | phát lại OTP mới | cookie `pendingToken` (bắt buộc) |

### Nhớ kỹ: "2 tầng code" trong response

```jsonc
{
  "code": 1000,            // ← code ENVELOPE, luôn 1000 khi không ném exception
  "message": "Success",
  "data": {
    "code": 1004,          // ← code NGHIỆP VỤ (SuccessCode) — cái FE thực sự rẽ nhánh
    "message": "New Otp created ...",
    "otpExpiresIn": 300,   // các field phụ, tùy nhánh mà có/không
    "cooldownRemaining": 60,
    "wrongRemaining": 5
  }
}
```

- `code=1000` = "không có exception". **Không** phản ánh trạng thái nghiệp vụ.
- **`data.code`** mới là trạng thái thật. Rất nhiều tình huống "chưa xong" (cooldown, đã đạt giới
  hạn, OTP còn hạn...) vẫn trả **HTTP 200** — vì đó là *luồng hợp lệ*, không phải lỗi.
- Chỉ khi có lỗi thật (sai mật khẩu trùng email, hết phiên...) mới ném `AppException` → `ErrorResponse`
  với mã `ErrorCode` (2xxx/3xxx) + HTTP status tương ứng.

---

## 2. Bốn "đồng hồ" OTP + pending token

Cả 3 endpoint đều đọc **cùng một bộ trạng thái** trong Redis rồi rẽ nhánh. Hiểu 5 khái niệm này
là hiểu toàn bộ luồng.

| Khái niệm | Redis key | TTL | Ý nghĩa |
|---|---|---|---|
| **attempts** | `otp:attempts:{userId}` | 3600s (fixed-window) | đã **phát** bao nhiêu OTP trong 1 giờ. Chống spam dài hạn. |
| **wrong** | `otp:wrong:{userId}` | 300s | đã **nhập sai** bao nhiêu lần. Chống brute-force. |
| **otp** | `otp:{userId}` | 300s | mã OTP 6 số hiện tại. Hết TTL = "OTP hết hạn". |
| **cooldown** | `otp:cooldown:{userId}` | 60s | cờ chặn resend trong 60s sau mỗi lần phát. |
| **pending token** | `pending:{token}` ↔ `pending:user:{userId}` | 10 phút | map 2 chiều token(cookie) ↔ userId, để server biết "OTP này của ai". |

Ngưỡng (hằng số trong `OtpServiceImpl`): `MAX_ATTEMPTS = 5`, `MAX_WRONG = 5`.

### Cách các bộ đếm hành xử (dễ nhầm)

- **attempts = fixed-window**: lần đầu `INCR` → set TTL 3600s; các lần sau **chỉ INCR, KHÔNG
  gia hạn TTL**. Nghĩa là cửa sổ 1 giờ tính từ lần phát đầu tiên, hết giờ thì reset về 0. Mục
  đích: chặn kiểu "cứ mỗi phút resend một lần mãi mãi".
- **wrong**: mỗi lần nhập sai `INCR` (+1), TTL 5 phút. `resetWrong` đưa về `0` khi phát OTP mới
  (để mã mới có lại đủ 5 lượt thử).
- **attempts** đếm **số lần PHÁT OTP**, **wrong** đếm **số lần NHẬP SAI** — hai thứ hoàn toàn
  khác nhau, đừng lẫn.

### Pending token (2 hàm quan trọng)

- `rotatePendingToken(userId)` — **xoay**: xóa token cũ, tạo UUID mới, ghi lại map 2 chiều (TTL
  10 phút). Dùng khi phát OTP mới (register nhánh mới, resend).
- `resolveOrCreatePendingToken(userId, cookieToken)` — **tái dùng**: ưu tiên token đang map sẵn
  theo user (gia hạn TTL); nếu không có thì chỉ chấp nhận token trong cookie **khi nó đúng là của
  user này**; cuối cùng mới tạo mới. Dùng ở các nhánh "tái dùng OTP cũ" để giữ nguyên phiên.

Cookie `pendingToken`: `HttpOnly`, `path=/`, `maxAge=600s` (10 phút), `secure=false` (môi trường
dev — production nên bật).

---

## 3. Register

`POST /api/v1/auth/register`

**Request** (`RegisterRequest`): `username`, `email`, `password`, `confirmPassword`, `fullName`.
Cookie `pendingToken` (tùy chọn). Hàm `register()` có `@Transactional`, trả `RegisterResponse`.

### Bước 1 — kiểm tra đầu vào & tính duy nhất (ném lỗi ngay)

| Kiểm tra | Lỗi (ErrorCode) |
|---|---|
| `password` (trim) ≠ `confirmPassword` (trim) | `2004 PASSWORD_MISMATCH` |
| email đang được tài khoản **active** dùng (`isEmailAlreadyInUse`) | `2002 EMAIL_ALREADY_IN_USE` |
| email thuộc tài khoản **bị ban** (`isEmailBanned`, tức `isDeleted=true`) | `2007 ACCOUNT_BANNED` |
| username đã bị **email khác** dùng (`isUserNameAlreadyInUse`) | `2003 USERNAME_ALREADY_IN_USE` |

### Bước 2 — tạo hoặc cập nhật User
- Nếu email thuộc **tài khoản chưa kích hoạt** (`isInactiveAccount`): tải user, cập nhật
  `username`/`fullName`/`password` (mã hóa BCrypt), giữ `isActive=false`, `isDeleted=false`, lưu.
  → Không tạo bản ghi trùng, cho phép "đăng ký lại" khi chưa verify.
- Ngược lại: tạo `User` mới với `isActive=false`, `isDeleted=false`, lưu.

### Bước 3 — cây quyết định OTP

Chụp snapshot (`attempts`, `wrong`, `otp còn/hết`, `cooldown`) rồi rẽ theo thứ tự
**attempts → wrong → otp → cooldown**:

| attempts | wrong | otp | cooldown | `data.code` | Hành động |
|:--:|:--:|:--:|:--:|:--:|---|
| ≥5 | ≥5 | – | – | **1010** | BLOCK — chờ TTL `attempts` hết rồi đăng ký lại |
| ≥5 | <5 | còn hạn | >0 | **1001** | tái dùng OTP cũ, cấp lại pending token |
| ≥5 | <5 | còn hạn | =0 | **1002** | tái dùng OTP cũ, cấp lại pending token |
| ≥5 | <5 | hết hạn | – | **1011** | BLOCK — chờ TTL `attempts` |
| <5 | ≥5 | – | – | **1006** | bảo user bấm **resend** để lấy mã mới |
| <5 | <5 | còn hạn | >0 | **1003** | tái dùng OTP cũ, cấp lại pending token |
| <5 | <5 | còn hạn | =0 | **1005** | tái dùng OTP cũ, cấp lại pending token |
| **<5** | **<5** | **hết hạn** | – | **1004 ⭐** | **PHÁT OTP MỚI + gửi email** |

Chỉ nhánh **1004** thực sự sinh mã: `rotatePendingToken` → `saveOtp` → `setCooldown` →
`incrementAttempts` → `resetWrong` → `sendOtpEmail`. Các nhánh còn lại chỉ *báo trạng thái* +
(nếu cần) cấp lại `pendingToken` để FE hiển thị đếm ngược.

### Response (`RegisterResponse`)
`code`, `message`, và tùy nhánh: `otpExpiresIn` (giây OTP còn sống), `cooldownRemaining` (giây
chờ resend), `wrongRemaining` (số lượt nhập còn lại), `attemptsTTL` (giây tới khi mở lại quyền
phát OTP). Field không áp dụng cho nhánh đó = `null`.

### Sơ đồ rút gọn
```
register(body, pendingToken?)
  ├─ password != confirm?        → throw 2004
  ├─ email active?               → throw 2002
  ├─ email banned?               → throw 2007
  ├─ username của người khác?     → throw 2003
  ├─ tạo/cập nhật User (isActive=false)
  └─ [cây quyết định OTP] → 1001/1002/1003/1004/1005/1006/1010/1011
        └─ chỉ 1004: sinh OTP + gửi email + set cookie pendingToken
```

---

## 4. Verify OTP

`POST /api/v1/auth/verify-otp`

**Request**: body `otp` (6 số) + cookie `pendingToken` (bắt buộc). `@Transactional`, trả
`VerifyOtpResponse`.

### Bước 1 — xác định phiên
- `pendingToken` trống → `3003 OTP_VERIFICATION_SESSION_EXPIRED`.
- Tra `pending:{token}` ra `userId`; không thấy hoặc parse lỗi → **xóa cookie** + `3003`.

### Bước 2 — chặn theo giới hạn (trước khi so khớp)

| attempts | wrong | otp | `data.code` | Ý nghĩa |
|:--:|:--:|:--:|:--:|---|
| ≥5 | ≥5 | – | **1010** | block, chờ TTL attempts → đăng ký lại |
| ≥5 | <5 | hết hạn | **1011** | block, chờ TTL attempts |
| ≥5 | <5 | **còn hạn** | → *đi so khớp* | vẫn cho verify! |
| <5 | ≥5 | – | **1006** | bảo user resend |
| <5 | <5 | hết hạn | **1007** | bảo user resend |
| <5 | <5 | **còn hạn** | → *đi so khớp* | |

> **Điểm mấu chốt:** khi `attempts≥5` nhưng **OTP còn hạn và wrong<5**, hệ thống **vẫn cho
> verify** (không block). Nghĩa là mã của lần phát thứ 5 vẫn dùng được — cân bằng chống-spam vs
> UX. Chỉ block khi nhập sai quá nhiều (`1010`) hoặc OTP đã hết hạn ở mức attempts≥5 (`1011`).

### Bước 3 — so khớp OTP
- Sai → `incrementWrong(uid)` → `3006 OTP_NOT_MATCH` (kèm `wrongRemaining`).
- Đúng → sang bước kích hoạt.

### Bước 4 — kích hoạt (OTP đúng)
1. `findByIdWithRoles(uid)`; nếu chưa có `ROLE_USER` → thêm role mặc định.
2. `setActive(true)` → `save`.
3. `otpService.clearAll(uid)` — xóa sạch `otp:*` + `pending:*`.
4. Xóa thẳng `pending:{token}` + `pending:user:{uid}` (phòng reverse index lệch).
5. Xóa cookie `pendingToken`.
6. `sendWelcomeEmail`.
7. Trả **`3001 VERIFY_OTP_SUCCESS`** → FE điều hướng sang **Login**.

---

## 5. Resend OTP

`POST /api/v1/auth/resend-otp`

**Request**: cookie `pendingToken` (bắt buộc). `@Transactional(readOnly=true)` (chỉ đọc DB;
ghi Redis + gửi mail nằm ngoài phạm vi transaction). Trả `ResendOtpResponse`.

### Bước 1 — xác định phiên (giống verify)
`pendingToken` trống / không tra được → `3003` (+ xóa cookie nếu tra Redis không ra).

### Bước 2 — cây quyết định (thứ tự **cooldown → attempts → otp → wrong**)

| cooldown | attempts | otp | wrong | `data.code` | Phát mã mới? |
|:--:|:--:|:--:|:--:|:--:|:--:|
| >0 | – | – | – | **1012** | ✗ (đang cooldown) |
| =0 | ≥5 | còn hạn | ≥5 | **1010** | ✗ (chờ TTL attempts) |
| =0 | ≥5 | còn hạn | <5 | **1008** | ✗ (chờ TTL attempts) |
| =0 | ≥5 | hết hạn | – | **1011** | ✗ (chờ TTL attempts) |
| =0 | **<5** | – | – | **1009 ⭐** | ✓ **escape hatch** |

Nhánh **1009**: `saveOtp` → `setCooldown` → `resetWrong` → `incrementAttempts` →
`rotatePendingToken` (đổi cookie) → `sendOtpEmail`. Vì `resetWrong` nên user có lại đủ 5 lượt
nhập cho mã mới.

> `attempts<5` là **escape hatch**: bất kể OTP cũ còn/hết hay wrong bao nhiêu, cứ phát mã mới
> (miễn không cooldown). Đây là lối thoát để user "bị kẹt" (nhập sai 5 lần) vẫn lấy được mã mới.

---

## 6. Bảng tra mã

### SuccessCode xuất hiện trong luồng này (nằm ở `data.code`, HTTP luôn 200)

| Code | Tên | Ở đâu | Nghĩa gọn |
|:--:|---|---|---|
| 1001 | OTP_ATTEMPTS_LIMIT_REACHED_COOLDOWN_ACTIVE | register | attempts≥5, tái dùng OTP, đang cooldown |
| 1002 | OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_NOT_REACHED | register | attempts≥5, tái dùng OTP, hết cooldown |
| 1003 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_COOLDOWN_ACTIVE | register | attempts<5, tái dùng OTP, đang cooldown |
| 1004 | NEW_OTP_CREATED | register | ⭐ phát OTP mới |
| 1005 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_OTP_NOT_EXPIRED | register | attempts<5, tái dùng OTP |
| 1006 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED | register · verify | nhập sai đủ 5 → bấm resend |
| 1007 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED | verify | OTP hết hạn (attempts<5) → bấm resend |
| 1008 | ...OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED | resend | attempts≥5 nhưng OTP còn hạn → verify tiếp |
| 1009 | RESEND_OTP_SUCCESS | resend | ⭐ phát OTP mới |
| 1010 | OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED | register · verify · resend | attempts≥5 & wrong≥5 → block |
| 1011 | OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED | register · verify · resend | attempts≥5 & OTP hết hạn → block |
| 1012 | COOLDOWN_ACTIVE | resend | đang cooldown |
| 3001 | VERIFY_OTP_SUCCESS | verify | kích hoạt thành công |
| 3006 | OTP_NOT_MATCH | verify | nhập sai mã |

### ErrorCode có thể ném (qua `AppException` → `ErrorResponse`)

| Code | Tên | HTTP | Ở đâu |
|:--:|---|:--:|---|
| 1001–1007 | *(validation)* BLANK/SIZE/EMAIL/USERNAME/PASSWORD/OTP | 400 | `@Valid` trên DTO |
| 2001 | USER_NOT_FOUND | 404 | verify · resend (user biến mất giữa chừng) |
| 2002 | EMAIL_ALREADY_IN_USE | 409 | register |
| 2003 | USERNAME_ALREADY_IN_USE | 409 | register |
| 2004 | PASSWORD_MISMATCH | 400 | register |
| 2007 | ACCOUNT_BANNED | 403 | register (email bị ban) |
| 3003 | OTP_VERIFICATION_SESSION_EXPIRED | 403 | verify · resend (mất pending token) |

---

## 7. Ghi chú & điểm dễ nhầm

- **OTP không bao giờ ở client** — chỉ nằm trong `otp:{userId}` (Redis, TTL 5 phút). Client chỉ
  giữ `pendingToken` (UUID) để chứng minh "tôi là phiên OTP của user X".
- **attempts vs wrong**: `attempts` = số lần *phát* OTP (chống spam mail, cửa sổ 1 giờ);
  `wrong` = số lần *nhập sai* (chống brute-force, TTL 5 phút). Đừng lẫn hai cái.
- **Verify vẫn chạy được ở attempts≥5** miễn OTP còn hạn & wrong<5 (xem §4). Đây là chủ ý, không
  phải bug.
- **cooldown chỉ chặn resend**, không chặn verify: trong 60s cooldown, user vẫn nhập được mã hiện
  tại nếu còn hạn.
- **Đăng ký lại khi chưa verify** sẽ *cập nhật* user cũ (cùng email chưa active), không tạo trùng.
- **Hiện trạng cần biết** (không thuộc phạm vi sửa của tài liệu này): trong `register`/`verifyOtp`,
  các thao tác Redis + gửi email nằm *bên trong* `@Transactional` DB. Nếu gửi mail lỗi, transaction
  DB rollback nhưng key Redis đã ghi có thể còn sót (dual-write). Đây là điểm đã ghi nhận để cải
  thiện sau (đẩy side-effect ra sau commit).

---

*Các chức năng còn lại (Login, Refresh token, Logout, JwtAuthFilter) sẽ có tài liệu riêng.*
