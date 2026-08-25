# TEST CASES - AUTHENTICATION MODULE (OTP Flow)

Bộ test tay (Postman) cho 4 hàm xử lý OTP: **register · verify-otp · resend-otp · login** (nhánh tài khoản chưa active).
Mỗi test có sẵn **JSON body để copy**. Code hiện tại trả trạng thái OTP bằng **HTTP 200 + `code` (SuccessCode)**; các lỗi nghiệp vụ/validate `throw` theo `ErrorCode`.

## Mục lục
- [Nhóm 0: Quy ước & Setup](#nhóm-0-quy-ước--setup)
- [Nhóm 1: REGISTER](#nhóm-1-register)
- [Nhóm 2: VERIFY OTP](#nhóm-2-verify-otp)
- [Nhóm 3: RESEND OTP](#nhóm-3-resend-otp)
- [Nhóm 4: LOGIN](#nhóm-4-login)
- [Nhóm 5: Kịch bản kết hợp (E2E)](#nhóm-5-kịch-bản-kết-hợp-e2e)

---

## Nhóm 0: Quy ước & Setup

**Endpoint** (chỉnh host/port/context cho khớp `application.properties`):
| Hàm | Method | URL |
|---|---|---|
| register | POST | `http://localhost:8080/api/v1/auth/register` |
| verify-otp | POST | `http://localhost:8080/api/v1/auth/verify-otp` |
| resend-otp | POST | `http://localhost:8080/api/v1/auth/resend-otp` |
| login | POST | `http://localhost:8080/api/v1/auth/login` |

**Dual-mode auth (Web cookie / Mobile header):**
- **Web:** cookie `pendingToken` (httpOnly, path `/`, MaxAge 600s) — Postman tự lưu & gửi lại giữa register → verify/resend.
- **Mobile:** header `X-Pending-Token: <token>` (lấy từ `data.pendingToken` trong response register/login-inactive). Server ưu tiên header, fallback cookie.
- `refreshToken` cookie: chỉ xuất hiện khi login **thành công** (tài khoản active); mobile nhận `data.refreshToken` trong body.

**Hằng số OTP:** OTP TTL 300s · cooldown 60s · attempts window 3600s (MAX_ATTEMPTS=5) · MAX_WRONG=5 · pending 600s.

**Response:** nhánh OTP-flow trả HTTP 200, thân trong `data` của APIResponse gồm `code`, `message`, và một phần `otpExpiresIn`/`cooldownRemaining`/`wrongRemaining`/`attemptsTTL`. Lỗi `throw` trả theo GlobalExceptionHandler (`code` + HTTP của ErrorCode).

**Lệnh redis-cli — lấy state & ép state (dùng để chạm nhánh khó):**
```bash
# Lấy userId + OTP đang sống (khỏi cần đọc email)
redis-cli GET pending:<pendingToken>     # -> userId
redis-cli GET otp:<userId>               # -> mã OTP để verify
redis-cli GET otp:wrong:<userId>
redis-cli GET otp:attempts:<userId>
redis-cli TTL otp:cooldown:<userId>

# Ép state
redis-cli SET otp:attempts:<userId> 5 EX 3600   # attempts >= 5
redis-cli SET otp:wrong:<userId> 5 EX 300        # wrong >= 5
redis-cli DEL otp:<userId>                        # OTP hết hạn
redis-cli SET otp:cooldown:<userId> 1 EX 60       # cooldown đang chạy
redis-cli DEL otp:cooldown:<userId>               # cooldown đã hết
redis-cli DEL pending:<pendingToken>              # pending hết hạn

# Reset sạch user test
redis-cli DEL otp:<id> otp:wrong:<id> otp:attempts:<id> otp:cooldown:<id> pending:user:<id>
```

> Re-register **cùng email** dùng lại **cùng userId** → state Redis theo userId còn nguyên ⇒ cơ chế để chạm các nhánh REUSE/BLOCK.

**Body chuẩn dùng lại nhiều lần:**

Register hợp lệ (USER):
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen",
  "accountType": "USER",
  "companyName": null
}
```

Register hợp lệ (EMPLOYER — bắt buộc `companyName`):
```json
{
  "username": "acme01",
  "email": "hr@acme.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "ACME HR",
  "accountType": "EMPLOYER",
  "companyName": "ACME Corp"
}
```

> ⚠️ `accountType` (enum từ `AccountType.java`): `USER` / `EMPLOYER` (mặc định USER). `companyName` **bắt buộc khi accountType = EMPLOYER** (thiếu → `2013 COMPANY_NAME_REQUIRED`). Sau verify OTP thành công, backend **tự tạo** Employee (USER) hoặc Company (EMPLOYER) — xem contract `03-register-otp.md`.
Login:
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

---

## Nhóm 1: REGISTER

Thứ tự cây quyết định: **attempts → wrong → otp → cooldown**.

### TC-RG-01: Đăng ký mới hợp lệ (clean state) → 1004 NEW

**Mục tiêu:** Xác nhận đăng ký cơ bản sinh OTP mới và set đủ Redis + cookie.

**Điều kiện tiên quyết:** Redis sạch, email chưa tồn tại trong DB.

**Request body:**
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Các bước thực hiện:**
1. POST `/register` với body trên.
2. Đọc response, header `Set-Cookie`, và Redis.

**Kỳ vọng:**
- HTTP 200, `code = 1004`, `otpExpiresIn = 300`, `cooldownRemaining = 60`, `wrongRemaining = 5`.
- Cookie `pendingToken` được set (MaxAge 600).
- Redis: `otp:{id}` (TTL~300), `otp:wrong:{id}=0`, `otp:attempts:{id}=1` (TTL~3600), `otp:cooldown:{id}=1` (TTL~60), `pending:{token}={id}`, `pending:user:{id}={token}`.
- DB: user mới `is_active=false`. Email OTP được gửi.

---

### TC-RG-02: Đăng ký lại cùng email — OTP còn hạn, cooldown còn → 1003 REUSE

**Mục tiêu:** Trong cooldown, register lại không sinh OTP mới mà tái dùng OTP cũ.

**Điều kiện tiên quyết:** Vừa chạy TC-RG-01 (cooldown đang chạy). Ghi lại `OTP_1 = redis-cli GET otp:{id}`.

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Các bước thực hiện:**
1. POST `/register` lại cùng email **trong vòng 60s**.

**Kỳ vọng:**
- HTTP 200, `code = 1003`, `cooldownRemaining > 0`, `otpExpiresIn > 0`, có `wrongRemaining`.
- `redis-cli GET otp:{id}` **vẫn = OTP_1** (không đổi), `otp:attempts` **không tăng** (vẫn 1), **không gửi email mới**.
- Cookie `pendingToken` được set lại.

---

### TC-RG-03: Đăng ký lại cùng email — OTP còn hạn, cooldown hết → 1005 REUSE

**Mục tiêu:** Hết cooldown nhưng OTP còn hạn ⇒ vẫn reuse, không sinh mới.

**Điều kiện tiên quyết:** Sau TC-RG-01, ép hết cooldown:
```bash
redis-cli DEL otp:cooldown:<id>
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:**
- HTTP 200, `code = 1005`, `cooldownRemaining = null`, `otpExpiresIn > 0`.
- OTP **không đổi**, `otp:attempts` **không tăng**.

---

### TC-RG-04: Đăng ký lại — OTP hết hạn → 1004 NEW (attempts tăng)

**Mục tiêu:** OTP hết hạn ⇒ sinh OTP mới, attempts +1.

**Điều kiện tiên quyết:** User inactive đã tồn tại; ép OTP hết hạn & cooldown hết:
```bash
redis-cli DEL otp:<id>
redis-cli DEL otp:cooldown:<id>
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:**
- HTTP 200, `code = 1004`.
- `redis-cli GET otp:{id}` ra **OTP mới** (khác trước), `otp:attempts` **+1**, email OTP gửi.

---

### TC-RG-05: attempts < 5, wrong ≥ 5 → 1006

**Mục tiêu:** Khi đã nhập sai đủ ngưỡng, register báo hướng dẫn resend.

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:wrong:<id> 5 EX 300
# đảm bảo attempts < 5
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 200, `code = 1006`, `wrongRemaining = 0`. Không sinh OTP mới.

---

### TC-RG-06: attempts ≥ 5, OTP còn hạn, cooldown còn → 1001 REUSE

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli SET otp:cooldown:<id> 1 EX 60
# otp:<id> còn hạn, otp:wrong < 5
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 200, `code = 1001`, có `cooldownRemaining`, `otpExpiresIn`, `wrongRemaining`. Reuse OTP, set lại cookie. Không gửi mail.

---

### TC-RG-07: attempts ≥ 5, OTP còn hạn, cooldown hết → 1002 REUSE

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:cooldown:<id>
# otp:<id> còn hạn, otp:wrong < 5
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 200, `code = 1002`, `otpExpiresIn`, `wrongRemaining`; **không** có `cooldownRemaining`.

---

### TC-RG-08: attempts ≥ 5, wrong < 5, OTP hết hạn → 1011 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:<id>
# otp:wrong < 5
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 200, `code = 1011`, có `attemptsTTL`. Không sinh OTP.

---

### TC-RG-09: attempts ≥ 5, wrong ≥ 5 → 1010 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli SET otp:wrong:<id> 5 EX 300
```

**Request body:** (giống TC-RG-01)
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 200, `code = 1010`, có `attemptsTTL`.

---

### TC-RG-10: password ≠ confirmPassword → PASSWORD_MISMATCH

**Request body:**
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password999",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** HTTP 400, `code = PASSWORD_MISMATCH`. Không tạo user, không sinh OTP.

---

### TC-RG-11: Email đã dùng bởi tài khoản ACTIVE → EMAIL_ALREADY_IN_USE

**Điều kiện tiên quyết:** Đã có user active với `alice@example.com` (chạy E2E-01 trước).

**Request body:**
```json
{
  "username": "alice02",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Two"
}
```

**Kỳ vọng:** HTTP 409, `code = EMAIL_ALREADY_IN_USE`.

---

### TC-RG-12: Email thuộc tài khoản bị ban → ACCOUNT_BANNED

**Điều kiện tiên quyết:** DB `is_deleted=true` cho user email này.

**Request body:**
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Kỳ vọng:** `code = ACCOUNT_BANNED`. Không sinh OTP.

---

### TC-RG-13: Username đã bị tài khoản khác dùng → USERNAME_ALREADY_IN_USE

**Điều kiện tiên quyết:** User B (email khác) đang dùng `username="alice01"`.

**Request body:**
```json
{
  "username": "alice01",
  "email": "bob@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Bob Tran"
}
```

**Kỳ vọng:** HTTP 409, `code = USERNAME_ALREADY_IN_USE`.

---

### TC-RG-14: Lỗi validate (6 sub-case)

Mỗi sub-case 1 request, kỳ vọng HTTP 400 + `code` ErrorCode tương ứng.

**a) username quá ngắn → INVALID_USERNAME**
```json
{ "username": "ab", "email": "alice@example.com", "password": "Password123", "confirmPassword": "Password123", "fullName": "Alice Nguyen" }
```

**b) username có ký tự lạ → INVALID_USERNAME_FORMAT**
```json
{ "username": "alice 01", "email": "alice@example.com", "password": "Password123", "confirmPassword": "Password123", "fullName": "Alice Nguyen" }
```

**c) email sai định dạng → INVALID_EMAIL**
```json
{ "username": "alice01", "email": "abc", "password": "Password123", "confirmPassword": "Password123", "fullName": "Alice Nguyen" }
```

**d) password < 8 → INVALID_PASSWORD**
```json
{ "username": "alice01", "email": "alice@example.com", "password": "123", "confirmPassword": "123", "fullName": "Alice Nguyen" }
```

**e) fullName trống → BLANK_FIELD**
```json
{ "username": "alice01", "email": "alice@example.com", "password": "Password123", "confirmPassword": "Password123", "fullName": "" }
```

**f) fullName > 100 ký tự → OUT_OF_SIZE**
```json
{ "username": "alice01", "email": "alice@example.com", "password": "Password123", "confirmPassword": "Password123", "fullName": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" }
```

---

## Nhóm 2: VERIFY OTP

Thứ tự cây: **attempts → wrong → otp → SO KHỚP**. Verify dùng cookie `pendingToken` (Postman tự gửi).

### TC-VR-01: Verify đúng → 3001 SUCCESS (luồng vàng)

**Mục tiêu:** Nhập đúng OTP ⇒ activate, dọn sạch Redis, xóa cookie.

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:** Vừa TC-RG-01. Lấy OTP:
```bash
redis-cli GET otp:<id>
```

**Request body:** (thay OTP thật vào)
```json
{ "otp": "123456" }
```

**Các bước thực hiện:**
1. POST `/verify-otp` với OTP đúng (cookie `pendingToken` tự gửi).

**Kỳ vọng:**
- HTTP 200, `code = 3001`.
- DB: `is_active=true`, có `ROLE_USER`.
- Redis: **toàn bộ** key OTP + pending bị xóa.
- Cookie `pendingToken` MaxAge=0. Email welcome được gửi.

---

### TC-VR-02: Verify sai 1 lần → 3006

**Điều kiện tiên quyết:** Vừa TC-RG-01, OTP còn hạn, wrong=0.

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Request body:**
```json
{ "otp": "000000" }
```

**Kỳ vọng:** HTTP 200, `code = 3006` (OTP_NOT_MATCH), `wrongRemaining = 4`, `otpExpiresIn > 0`. Redis `otp:wrong:{id}=1`. User vẫn inactive.

---

### TC-VR-03: Sai 5 lần → lần 6 ra 1006

**Mục tiêu:** Sau 5 lần sai, verify bị chặn (att<5, wrong≥5).

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:** OTP còn hạn, attempts<5, wrong=0.

**Request body (lặp 5 lần):**
```json
{ "otp": "000000" }
```

**Các bước thực hiện:**
1. Gửi 5 lần body sai:
   - Lần 1→4: `code = 3006`, `wrongRemaining` 4→1.
   - Lần 5: `code = 3006`, `wrongRemaining = 0`, `otp:wrong:{id}=5`.
2. Gửi lần 6 (sai hoặc đúng):

**Kỳ vọng:** Lần 6 → `code = 1006`, `wrongRemaining = 0`.
**Kỳ vọng sai (bug):** lần 6 vẫn so khớp OTP (đáng lẽ phải chặn).

---

### TC-VR-04: attempts < 5, wrong < 5, OTP hết hạn → 1007

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:**
```bash
redis-cli DEL otp:<id>
```

**Request body:**
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 200, `code = 1007`, `otpExpiresIn = 0`, có `wrongRemaining`.

---

### TC-VR-05: attempts ≥ 5, wrong < 5, OTP còn hạn → vẫn verify được

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
# otp:<id> còn hạn, wrong < 5
redis-cli GET otp:<id>   # lấy OTP đúng
```

**Request body:** (OTP đúng)
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 200, `code = 3001`. Activate thành công (qua được dù attempts≥5 vì OTP còn hạn).

---

### TC-VR-06: attempts ≥ 5, wrong < 5, OTP hết hạn → 1011

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:<id>
```

**Request body:**
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 200, `code = 1011`, có `attemptsTTL`.

---

### TC-VR-07: attempts ≥ 5, wrong ≥ 5 → 1010

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli SET otp:wrong:<id> 5 EX 300
```

**Request body:**
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 200, `code = 1010`, có `attemptsTTL`.

---

### TC-VR-08: Không có cookie pendingToken → OTP_VERIFICATION_SESSION_EXPIRED

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:** Xóa cookie `pendingToken` trong Postman (tab Cookies).

**Request body:**
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 403, `code = OTP_VERIFICATION_SESSION_EXPIRED`.

---

### TC-VR-09: Cookie còn nhưng pending hết trong Redis → session expired + xóa cookie

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Điều kiện tiên quyết:** Sau register, xóa pending:
```bash
redis-cli DEL pending:<token>
```

**Request body:**
```json
{ "otp": "123456" }
```

**Kỳ vọng:** HTTP 403, `code = OTP_VERIFICATION_SESSION_EXPIRED`; response set cookie `pendingToken` MaxAge=0.

---

### TC-VR-10: OTP sai kích thước (validate) → OTP_OUT_OF_SIZE

```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```

**Request body:**
```json
{ "otp": "123" }
```

**Kỳ vọng:** HTTP 400, `code = OTP_OUT_OF_SIZE` (chặn ở validation, chưa vào service).

---

## Nhóm 3: RESEND OTP

Thứ tự cây: **cooldown → attempts → otp → wrong**. Body **để trống** (`/resend-otp` không nhận body), chỉ cần cookie `pendingToken`.

> Trong Postman: chọn Body = **none** (hoặc raw `{}`), endpoint chỉ đọc cookie.

### TC-RS-01: cooldown đang chạy → 1012 (chặn)

**Điều kiện tiên quyết:** Vừa register (cooldown>0). Ghi `OTP_1 = redis-cli GET otp:{id}`.

**Request body:** _(để trống)_

**Các bước thực hiện:**
1. POST `/resend-otp` ngay (trong 60s).

**Kỳ vọng:**
- HTTP 200, `code = 1012`, `cooldownRemaining > 0`, có `otpExpiresIn`, `wrongRemaining`.
- `otp:attempts` **không tăng**, `GET otp:{id}` **= OTP_1** (không ghi đè), không gửi mail.

**Kỳ vọng sai (bug):** ra 1009/success ⇒ backend không chặn cooldown.

---

### TC-RS-02: cooldown hết, attempts < 5 → 1009 NEW (escape hatch)

**Mục tiêu:** Hết cooldown + attempts<5 ⇒ cấp OTP mới (không xét otp/wrong).

**Điều kiện tiên quyết:**
```bash
redis-cli DEL otp:cooldown:<id>
# attempts < 5
redis-cli GET otp:<id>   # ghi OTP_1
```

**Request body:** _(để trống)_

**Kỳ vọng:**
- HTTP 200, `code = 1009`.
- `GET otp:{id}` đổi **OTP_2** (ghi đè OTP_1), `otp:wrong:{id}=0` (reset), `otp:attempts` **+1**, `otp:cooldown=1` (60s), cookie `pendingToken` **đổi giá trị** (rotation), mail OTP mới gửi.

---

### TC-RS-03: cooldown hết, attempts ≥ 5, OTP còn, wrong < 5 → 1008 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:cooldown:<id>
# otp:<id> còn hạn, wrong < 5
```

**Request body:** _(để trống)_

**Kỳ vọng:** HTTP 200, `code = 1008`, có `attemptsTTL`, `otpExpiresIn`, `wrongRemaining`. Không sinh OTP mới, attempts không tăng.

---

### TC-RS-04: cooldown hết, attempts ≥ 5, OTP còn, wrong ≥ 5 → 1010 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:cooldown:<id>
redis-cli SET otp:wrong:<id> 5 EX 300
# otp:<id> còn hạn
```

**Request body:** _(để trống)_

**Kỳ vọng:** HTTP 200, `code = 1010`, có `attemptsTTL`.

---

### TC-RS-05: cooldown hết, attempts ≥ 5, OTP hết hạn → 1011 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:<id>
redis-cli DEL otp:cooldown:<id>
```

**Request body:** _(để trống)_

**Kỳ vọng:** HTTP 200, `code = 1011`, có `attemptsTTL`.

---

### TC-RS-06: Không có pendingToken → OTP_VERIFICATION_SESSION_EXPIRED

**Điều kiện tiên quyết:** Xóa cookie `pendingToken`.

**Request body:** _(để trống)_

**Kỳ vọng:** HTTP 403, `code = OTP_VERIFICATION_SESSION_EXPIRED`.

---

### TC-RS-07: pending hết trong Redis → session expired + xóa cookie

**Điều kiện tiên quyết:** `redis-cli DEL pending:<token>`.

**Request body:** _(để trống)_

**Kỳ vọng:** HTTP 403, `code = OTP_VERIFICATION_SESSION_EXPIRED`; cookie `pendingToken` MaxAge=0.

---

## Nhóm 4: LOGIN

Login xác thực email+password trước. **Active** → 4001 + refreshToken cookie. **Chưa active** → `LoginInactiveResponse` (HTTP 200) theo cây **attempts → wrong → otp → cooldown**.

### TC-LG-01: Login tài khoản ACTIVE → 4001 + refreshToken

**Điều kiện tiên quyết:** User active (chạy E2E-01).

**Request body:**
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

**Kỳ vọng:**
- HTTP 200, `code = 4001`, body có `accessToken`, `id`, `username`, `roles`.
- Cookie `refreshToken` được set. Redis: `session:{sid}` (status ACTIVE), `user:sessions:{userId}` chứa `sid`. **Không** đụng key OTP.

---

### TC-LG-02: Sai mật khẩu → INVALID_CREDENTIALS

**Request body:**
```json
{
  "email": "alice@example.com",
  "password": "WrongPassword",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

**Kỳ vọng:** HTTP 401, `code = INVALID_CREDENTIALS`. Không sinh OTP/session.

---

### TC-LG-03: Email không tồn tại → INVALID_CREDENTIALS

**Request body:**
```json
{
  "email": "notexist@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

**Kỳ vọng:** HTTP 401, `code = INVALID_CREDENTIALS`.

---

### TC-LG-04: Tài khoản bị ban (deleted), mật khẩu đúng → ACCOUNT_BANNED

**Điều kiện tiên quyết:** DB `is_deleted=true`, mật khẩu đúng.

**Request body:**
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

**Kỳ vọng:** `code = ACCOUNT_BANNED`.

---

### TC-LG-05: Validate login (4 sub-case)

**a) thiếu deviceId → BLANK_FIELD**
```json
{ "email": "alice@example.com", "password": "Password123", "deviceName": "Postman" }
```
**b) deviceId không phải UUID → 400**
```json
{ "email": "alice@example.com", "password": "Password123", "deviceId": "not-a-uuid", "deviceName": "Postman" }
```
**c) deviceName > 100 ký tự → OUT_OF_SIZE**
```json
{ "email": "alice@example.com", "password": "Password123", "deviceId": "11111111-1111-1111-1111-111111111111", "deviceName": "PPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPP" }
```
**d) email sai định dạng → INVALID_EMAIL**
```json
{ "email": "abc", "password": "Password123", "deviceId": "11111111-1111-1111-1111-111111111111", "deviceName": "Postman" }
```

---

> **Các TC-LG-10 → 17 dưới đây dùng tài khoản INACTIVE** (đã register, chưa verify). Body login giống nhau, chỉ khác state Redis. Lấy `userId` từ `redis-cli GET pending:<token>` hoặc DB.

**Body login dùng chung cho TC-LG-10 → 17:**
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```

### TC-LG-10: inactive, attempts < 5, wrong < 5, OTP hết hạn → 4002 NEW

**Điều kiện tiên quyết:**
```bash
redis-cli DEL otp:<id>
redis-cli DEL otp:cooldown:<id>
# attempts < 5, wrong < 5
```

**Kỳ vọng:** HTTP 200, `code = 4002`, `otpExpiresIn=300`, `cooldownRemaining=60`, `wrongRemaining=5`. OTP mới + mail, attempts++, cookie `pendingToken` set. **Không** có refreshToken.

---

### TC-LG-11: inactive, OTP còn, cooldown còn → 4003 REUSE

**Điều kiện tiên quyết:** Vừa TC-LG-10 (cooldown chạy). Ghi `OTP_1`.

**Kỳ vọng:** HTTP 200, `code = 4003`, `cooldownRemaining>0`, OTP **không đổi**, attempts **không tăng**, không gửi mail, set lại `pendingToken`.

---

### TC-LG-12: inactive, OTP còn, cooldown hết → 4005 REUSE

**Điều kiện tiên quyết:**
```bash
redis-cli DEL otp:cooldown:<id>
# otp:<id> còn hạn, attempts<5, wrong<5
```

**Kỳ vọng:** HTTP 200, `code = 4005`, `cooldownRemaining=0`, `otpExpiresIn>0`, OTP không đổi.

---

### TC-LG-13: inactive, attempts < 5, wrong ≥ 5 → 1006

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:wrong:<id> 5 EX 300
# attempts < 5
```

**Kỳ vọng:** HTTP 200, `code = 1006`, `wrongRemaining = 0`.

---

### TC-LG-14: inactive, attempts ≥ 5, OTP còn, cooldown còn → 4004 REUSE

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli SET otp:cooldown:<id> 1 EX 60
# otp:<id> còn hạn, wrong < 5
```

**Kỳ vọng:** HTTP 200, `code = 4004`, `cooldownRemaining>0`, `otpExpiresIn`, `wrongRemaining`.

---

### TC-LG-15: inactive, attempts ≥ 5, OTP còn, cooldown hết → 1008 REUSE

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:cooldown:<id>
# otp:<id> còn hạn, wrong < 5
```

**Kỳ vọng:** HTTP 200, `code = 1008`, `cooldownRemaining=0`, `otpExpiresIn`, `wrongRemaining`.

---

### TC-LG-16: inactive, attempts ≥ 5, OTP hết hạn → 1011 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli DEL otp:<id>
# wrong < 5
```

**Kỳ vọng:** HTTP 200, `code = 1011`, có `attemptsTTL`.

---

### TC-LG-17: inactive, attempts ≥ 5, wrong ≥ 5 → 1010 BLOCK

**Điều kiện tiên quyết:**
```bash
redis-cli SET otp:attempts:<id> 5 EX 3600
redis-cli SET otp:wrong:<id> 5 EX 300
```

**Kỳ vọng:** HTTP 200, `code = 1010`, có `attemptsTTL`.

---

## Nhóm 5: Kịch bản kết hợp (E2E)

### TC-E2E-01: Register → Verify đúng ngay (luồng vàng)

**Các bước thực hiện:**
1. POST `/register`:
```json
{
  "username": "alice01",
  "email": "alice@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Alice Nguyen"
}
```
→ `code = 1004`. Lấy OTP: `redis-cli GET otp:<id>`.
2. POST `/verify-otp`:
```json
{ "otp": "<OTP vừa lấy>" }
```
→ `code = 3001`.

**Kỳ vọng:** user active, Redis sạch toàn bộ key, cookie `pendingToken` xóa, mail welcome gửi.

---

### TC-E2E-02: Register → sai 3 lần → đúng

**Các bước thực hiện:**
1. Register (body như E2E-01) → 1004.
2. Verify sai 3 lần, mỗi lần body:
```json
{ "otp": "000000" }
```
→ 3006 (`wrongRemaining` 4→2), `otp:wrong:{id}=3`.
3. Verify đúng:
```json
{ "otp": "<OTP thật>" }
```
→ 3001.

**Kỳ vọng:** Activate thành công (wrong<5, OTP còn hạn).

---

### TC-E2E-03: Register → OTP hết hạn → resend → verify

**Các bước thực hiện:**
1. Register → 1004.
2. `redis-cli DEL otp:<id>`. Verify OTP cũ:
```json
{ "otp": "123456" }
```
→ **1007** (OTP hết hạn).
3. `redis-cli DEL otp:cooldown:<id>`. POST `/resend-otp` _(body trống)_ → **1009**, OTP_2 (attempts=2, wrong=0).
4. Verify OTP_2:
```json
{ "otp": "<OTP_2>" }
```
→ 3001.

---

### TC-E2E-04: Register → sai 5 lần → resend (reset wrong) → verify

**Mục tiêu:** Chứng minh resend reset `otp:wrong` về 0.

**Các bước thực hiện:**
1. Register → 1004. `redis-cli DEL otp:cooldown:<id>`.
2. Verify sai 5 lần (`{ "otp": "000000" }`) → `otp:wrong:{id}=5` (lần 5 `wrongRemaining=0`).
3. Verify lần nữa → **1006** (att<5, wrong≥5).
4. POST `/resend-otp` _(body trống)_ → **1009**: `otp:wrong` reset 0, OTP_2.
5. Verify sai 1 lần OTP_2 (`{ "otp": "000000" }`) → **3006**, `wrongRemaining = 4`.
6. Verify đúng OTP_2 → 3001.

**Điểm verify đặc biệt:** bước 5 phải ra `wrongRemaining = 4`. Nếu ra 0/1 ⇒ bug cộng dồn wrong.

---

### TC-E2E-05: Register (chưa verify) → Login → verify qua pending của login

**Các bước thực hiện:**
1. Register `bob` (đổi email/username), → 1004, **không** verify.
```json
{
  "username": "bob01",
  "email": "bob@example.com",
  "password": "Password123",
  "confirmPassword": "Password123",
  "fullName": "Bob Tran"
}
```
2. `redis-cli DEL otp:<id>` + `redis-cli DEL otp:cooldown:<id>`. POST `/login`:
```json
{
  "email": "bob@example.com",
  "password": "Password123",
  "deviceId": "22222222-2222-2222-2222-222222222222",
  "deviceName": "Postman"
}
```
→ **4002**, nhận cookie `pendingToken` mới.
3. `redis-cli GET otp:<id>` → POST `/verify-otp` `{ "otp": "<OTP>" }` → 3001.
4. POST `/login` lại (body như bước 2) → **4001** + refreshToken.

---

### TC-E2E-06: Register cấp OTP → Login cùng account inactive REUSE đúng OTP đó

**Các bước thực hiện:**
1. Register (body E2E-01) → 1004, OTP_1 (cooldown chạy).
2. POST `/login` (account inactive) trong 60s:
```json
{
  "email": "alice@example.com",
  "password": "Password123",
  "deviceId": "11111111-1111-1111-1111-111111111111",
  "deviceName": "Postman"
}
```
→ **4003** REUSE.

**Kỳ vọng:** `GET otp:{id}` vẫn OTP_1, attempts không tăng, không gửi mail. (Nếu `DEL otp:cooldown` trước login → **4005**.)
3. Verify OTP_1 → 3001.

---

### TC-E2E-07: Resend rotate pending → token cũ chết

**Các bước thực hiện:**
1. Register → `pendingToken = T1`. `redis-cli DEL otp:cooldown:<id>`.
2. POST `/resend-otp` _(body trống)_ → 1009, cookie đổi `pendingToken = T2`; `pending:T1` bị xóa.
3. Trong Postman, set tay header `Cookie: pendingToken=T1` (cũ) rồi POST `/verify-otp`:
```json
{ "otp": "123456" }
```
→ `code = OTP_VERIFICATION_SESSION_EXPIRED` (403).
4. Dùng cookie T2 + OTP mới → 3001.

---

## Phụ lục A — Bảng tra mã SuccessCode
| code | tên | xuất hiện ở |
|---|---|---|
| 1001 | OTP_ATTEMPTS_LIMIT_REACHED_COOLDOWN_ACTIVE | register (RG-06) |
| 1002 | OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_NOT_REACHED | register (RG-07) |
| 1003 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_COOLDOWN_ACTIVE | register (RG-02) |
| 1004 | NEW_OTP_CREATED | register (RG-01/04) |
| 1005 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_OTP_NOT_EXPIRED | register (RG-03) |
| 1006 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED | register / verify / login |
| 1007 | OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED | verify (VR-04) |
| 1008 | OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED | resend / login |
| 1009 | RESEND_OTP_SUCCESS | resend (RS-02) |
| 1010 | OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED | cả 4 hàm |
| 1011 | OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED | cả 4 hàm |
| 1012 | COOLDOWN_ACTIVE | resend (RS-01) |
| 3001 | VERIFY_OTP_SUCCESS | verify (VR-01) |
| 3006 | OTP_NOT_MATCH | verify (VR-02) |
| 4001 | LOGIN_SUCCESS | login (LG-01) |
| 4002 | LOGIN_INACTIVE_OTP_SENT | login (LG-10) |
| 4003 | LOGIN_INACTIVE_OTP_REUSED | login (LG-11) |
| 4004 | OTP_ATTEMPTS_LIMIT_REACHED_AND_COOLDOWN_ACTIVE | login (LG-14) |
| 4005 | LOGIN_INACTIVE_OTP_REUSED_NO_COOLDOWN | login (LG-12) |

## Phụ lục B — Checklist xác minh sau mỗi TC
- [ ] HTTP status đúng (200 cho OTP-flow; 4xx cho throw).
- [ ] `code` đúng.
- [ ] TTL hợp lý (`otpExpiresIn` ≤ 300, `cooldownRemaining` ≤ 60, không âm — đã clamp `Math.max(0,…)`).
- [ ] Cookie `pendingToken` set/giữ/xóa đúng.
- [ ] Redis: `otp:{id}` đổi/giữ đúng; `otp:attempts` chỉ tăng ở NEW (1004/1009/4002); `otp:wrong` chỉ reset ở resend NEW & verify success.
- [ ] Email gửi đúng lúc (NEW 1004/1009/4002; welcome ở 3001).
- [ ] DB `is_active` chỉ `true` sau 3001.
