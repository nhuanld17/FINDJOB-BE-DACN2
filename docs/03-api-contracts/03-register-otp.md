# FE API Contract - Register + OTP

> **Cập nhật 2026-08-06:** Contract viết lại theo implementation hiện tại — register có `accountType`/`companyName`, response trả `RegisterResponse`/`VerifyOtpResponse`/`ResendOtpResponse` (không còn `data: null`), dual-mode pendingToken (cookie web / header `X-Pending-Token` mobile). Đối chiếu trực tiếp với `RegisterRequest`, `AuthController`, `SuccessCode`.

## 1. Scope
This document covers only:
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/verify-otp`
- `POST /api/v1/auth/resend-otp`

No login/refresh/logout is included here.

## 2. Base
- Base path: `/api/v1/auth`
- Content type: `application/json`

### 2.1 Dual-mode pendingToken

| | Web (React SPA) | Mobile (React Native) |
|---|---|---|
| **pendingToken** | Cookie HttpOnly `pendingToken` — browser tự gửi | **Header `X-Pending-Token: <token>`** — app nhận token từ response body |
| **Vì sao có header?** | Cookie có sẵn trong browser | Mobile không có cookie manager tự động → server trả token trong body, app tự giữ |

> Server nhận pendingToken theo thứ tự: **header `X-Pending-Token` trước**, không có thì dùng cookie.

## 3. Cookie Contract (`pendingToken` — CHỈ cho Web)
Server sets cookie:
- Name: `pendingToken`
- `HttpOnly: true`
- `Secure: true`
- `Path: /`
- `Max-Age: 600` seconds (10 minutes)

Local dev note:
- `Secure=true` → browser chỉ gửi cookie qua HTTPS. Web dev trên HTTP có thể fail OTP flow.
- **Mobile không bị ảnh hưởng** (dùng header).

## 4. Response Format

### 4.1 Success format
3 endpoints trả `APIResponse` với `data` là object (xem chi tiết từng endpoint):

```json
{
  "code": 1000,
  "message": "Success",
  "data": {}
}
```

### 4.2 Error format
Business/validation errors return `ErrorResponse`:

```json
{
  "status": 400,
  "code": 3006,
  "message": "OTP does not match",
  "errors": null,
  "timestamp": "2026-04-25T13:00:00Z"
}
```

## 5. Endpoint Details

## 5.1 `POST /register`
Create inactive user, start OTP session, lưu intent đăng ký (`accountType` + `companyName`).

Request body:

```json
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "12345678",
  "confirmPassword": "12345678",
  "fullName": "John Doe",
  "accountType": "EMPLOYER",
  "companyName": "ABC Tech JSC"
}
```

Validation rules (từ `RegisterRequest`):
- `username`: not blank, 3–50, regex `^[a-zA-Z0-9_]+$`
- `email`: not blank, valid email
- `password`: not blank, min 8, max 72
- `confirmPassword`: not blank
- `fullName`: not blank, max 100
- `accountType`: enum `USER` | `EMPLOYER` — **null → mặc định USER**
- `companyName`: max 255 — **bắt buộc khi `accountType = EMPLOYER`** (check ở service → `2013 COMPANY_NAME_REQUIRED`)

Success — trả `APIResponse<RegisterResponse>` (KHÔNG còn `data: null`):

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "code": 1004,
    "message": "New Otp created and send to your email, please check your email",
    "otpExpiresIn": 300,
    "cooldownRemaining": 0,
    "wrongRemaining": 5,
    "attemptsTTL": 600,
    "pendingToken": "eyJhbGciOiJIUzUxMiJ9..."   // null với web (dùng cookie)
  }
}
```

Giải thích:
- `code`: SuccessCode OTP (1001–1013) — cho FE biết trạng thái OTP hiện tại
- `otpExpiresIn`: giây còn lại đến khi OTP hết hạn
- `cooldownRemaining`: giây còn lại phải chờ để resend
- `wrongRemaining`: số lượt nhập OTP sai còn lại
- `attemptsTTL`: TTL còn lại của lượt gửi OTP
- `pendingToken`: mobile dùng làm header `X-Pending-Token`; web dùng cookie

Main error codes:
- `2002 EMAIL_ALREADY_IN_USE` (email đã có tài khoản ACTIVE)
- `2003 USERNAME_ALREADY_IN_USE`
- `2004 PASSWORD_MISMATCH`
- `2007 ACCOUNT_BANNED`
- `2013 COMPANY_NAME_REQUIRED` (EMPLOYER thiếu companyName)
- `2009 RESEND_OTP_BLOCKED`
- Validation codes: `1001` (blank), `1002` (size), `1003` (email), `1004`/`1005` (username), `1006` (password)

## 5.2 `POST /verify-otp`
Verify OTP và kích hoạt tài khoản.

Request:
- Mobile: header `X-Pending-Token: <token>` + body
- Web: cookie `pendingToken` + body

```json
{
  "otp": "123456"
}
```

Validation rules:
- `otp`: not blank, length = 6

Success — trả `APIResponse<VerifyOtpResponse>`:

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "code": 3001,
    "message": "OTP verified successfully",
    "otpExpiresIn": null,
    "cooldownRemaining": null,
    "wrongRemaining": null,
    "attemptsTTL": null,
    "pendingToken": null
  }
}
```

**Quan trọng — sau khi verify thành công, server TỰ ĐỘNG tạo relation** (cùng transaction):
- `accountType = EMPLOYER` → **tự tạo Company** (owner = user, tên = companyName đã lưu)
- `accountType = USER` → **tự tạo Employee profile** (để user follow company / apply job được)
- Clear `pendingAccountType`/`pendingCompanyName`, xoá OTP state Redis, clear cookie `pendingToken`

Main error codes:
- `3003 OTP_VERIFICATION_SESSION_EXPIRED` (session hết hạn / thiếu pendingToken)
- `3010 OTP_VERIFY_LIMIT_REACHED` (attempt > 5)
- `3004 MAX_WRONG_OTP` (nhập sai quá số lần)
- `3005 OTP_EXPIRED`
- `3006 OTP_INVALID` (sai mã — vẫn còn lượt thử)
- `2001 USER_NOT_FOUND`

## 5.3 `POST /resend-otp`
Gửi lại OTP mới cho session hiện tại.

Request:
- Mobile: header `X-Pending-Token: <token>` (không body)
- Web: cookie `pendingToken` (không body)

Success — trả `APIResponse<ResendOtpResponse>` (cùng shape RegisterResponse):

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "code": 1009,
    "message": "Resend new otp success",
    "otpExpiresIn": 300,
    "cooldownRemaining": 60,
    "wrongRemaining": 5,
    "attemptsTTL": 600,
    "pendingToken": "eyJhbGciOiJIUzUxMiJ9..."
  }
}
```

Main error codes:
- `3003 OTP_VERIFICATION_SESSION_EXPIRED`
- `1012 COOLDOWN_ACTIVE` (đang trong cooldown — trả qua data.code, không phải lỗi)
- `2009 RESEND_OTP_BLOCKED`
- `2001 USER_NOT_FOUND`

## 6. Attempt Rules (Important for FE)
- **Send limit** (`register`, `resend-otp`): blocked khi `attempt >= 5`
- **Verify limit** (`verify-otp`): blocked khi `attempt > 5` → `3010 OTP_VERIFY_LIMIT_REACHED`

Nghĩa là:
- User vẫn verify được OTP cuối đã gửi ở attempt 5.
- User không thể gửi OTP mới khi attempt đã đạt 5.

> ⚠️ **Lưu ý semantics:** `wrongRemaining` = **số lần còn được phép thử** (giảm dần mỗi lần nhập sai), KHÔNG phải "số lần đã nhập sai".

## 7. FE Handling Suggestions
- `3003 OTP_VERIFICATION_SESSION_EXPIRED`: clear màn OTP, quay lại register/start flow
- `1012 COOLDOWN_ACTIVE` (data.code): giữ user ở màn OTP, show cooldown, disable resend
- `2009 RESEND_OTP_BLOCKED`: disable resend, show "send limit reached"
- `3010 OTP_VERIFY_LIMIT_REACHED`: lock verify, yêu cầu restart flow
- `3006 OTP_INVALID`: show inline error trên field OTP
- `3005 OTP_EXPIRED`: show expired state, yêu cầu resend
- Mobile: luôn giữ `pendingToken` (từ response register/login-inactive) và gửi trong header `X-Pending-Token` — token đổi mỗi lần resend thành công.

## 8. Recommended Popup Messages

- `3003 OTP_VERIFICATION_SESSION_EXPIRED`
  - Popup title: `Session expired`
  - Popup message: `Your OTP session has expired. Please register again to receive a new OTP.`

- `1012 COOLDOWN_ACTIVE`
  - Popup title: `Please wait`
  - Popup message: `You just requested an OTP. Please wait a moment before resending.`

- `2009 RESEND_OTP_BLOCKED`
  - Popup title: `OTP send limit reached`
  - Popup message: `You have reached the OTP send limit. Please try again later.`

- `3010 OTP_VERIFY_LIMIT_REACHED`
  - Popup title: `Verification blocked`
  - Popup message: `This OTP session can no longer be verified. Please restart the registration flow.`

- `3006 OTP_INVALID`
  - Popup title: `Invalid OTP`
  - Popup message: `The OTP you entered is incorrect. Please try again.`

- `3005 OTP_EXPIRED`
  - Popup title: `OTP expired`
  - Popup message: `This OTP has expired. Please request a new OTP.`

- `3004 MAX_WRONG_OTP`
  - Popup title: `Too many wrong attempts`
  - Popup message: `You entered the wrong OTP too many times. Please restart the flow and request a new OTP.`

- `2002 EMAIL_ALREADY_IN_USE`
  - Popup title: `Email already used`
  - Popup message: `This email is already in use. Please use another email.`

- `2003 USERNAME_ALREADY_IN_USE`
  - Popup title: `Username already used`
  - Popup message: `This username is already taken. Please choose another one.`

- `2004 PASSWORD_MISMATCH`
  - Popup title: `Password mismatch`
  - Popup message: `Password and confirm password do not match.`

- `2013 COMPANY_NAME_REQUIRED`
  - Popup title: `Company name required`
  - Popup message: `Please enter your company name to register as an employer.`

- `2007 ACCOUNT_BANNED`
  - Popup title: `Account unavailable`
  - Popup message: `An account with this email is banned. Please contact support.`

- `1001–1007` (validation)
  - Popup title: `Invalid input`
  - Popup message: `Please review your input and try again.`
