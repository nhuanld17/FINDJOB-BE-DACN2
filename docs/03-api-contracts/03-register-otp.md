# FE API Contract - Register + OTP

## 1. Scope
This document covers only:
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/verify-otp`
- `POST /api/v1/auth/resend-otp`

No login/refresh/logout is included here.

## 2. Base
- Base path: `/api/v1/auth`
- Content type: `application/json`
- FE must call with credentials included:
  - `fetch`: `credentials: "include"`
  - `axios`: `withCredentials: true`

Reason: server uses HttpOnly cookie `pendingToken` for OTP session.

## 3. Cookie Contract (`pendingToken`)
Server sets cookie:
- Name: `pendingToken`
- `HttpOnly: true`
- `Secure: true`
- `Path: /`
- `Max-Age: 600` seconds (10 minutes)

Note for local dev:
- Because `Secure=true`, browser only sends this cookie over HTTPS.
- If FE/BE are running on plain HTTP, OTP flow in browser can fail due to cookie not being attached.

## 4. Response Format

### 4.1 Success format
All 3 endpoints return wrapper `APIResponse`:

```json
{
  "code": 1000,
  "message": "Success",
  "data": null
}
```

### 4.2 Error format
Business/validation errors return `ErrorResponse`:

```json
{
  "status": 400,
  "code": 3006,
  "message": "OTP is not true",
  "errors": null,
  "timestamp": "2026-04-25T13:00:00Z"
}
```

Validation error example:

```json
{
  "status": 400,
  "code": null,
  "message": "Validation failed",
  "errors": {
    "email": [
      "Invalid email format"
    ]
  },
  "timestamp": "2026-04-25T13:00:00Z"
}
```

## 5. Endpoint Details

## 5.1 `POST /register`
Create or update inactive user and start OTP session.

Request body:

```json
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "12345678",
  "confirmPassword": "12345678",
  "fullName": "John Doe"
}
```

Validation rules:
- `username`: not blank, 3-50, regex `^[a-zA-Z0-9_]+$`
- `email`: not blank, valid email
- `password`: not blank, min 8
- `confirmPassword`: not blank
- `fullName`: not blank, max 100

Success:
- Returns `APIResponse.success()`
- Sets/renews `pendingToken` cookie
- May generate OTP and send email (or keep current OTP state if in cooldown)

Main error codes:
- `2002 EMAIL_ALREADY_IN_USE`
- `2003 USERNAME_ALREADY_IN_USE`
- `2004 PASSWORD_MISMATCH`
- `2007 ACCOUNT_BANNED`
- `2008 OTP_SEND_LIMIT_REACHED`
- Validation codes (`1001`, `1003`, `1004`, `1005`, `1006`, `1002`)

## 5.2 `POST /verify-otp`
Verify OTP using `pendingToken` cookie and activate account.

Request body:

```json
{
  "otp": "123456"
}
```

Validation rules:
- `otp`: not blank, length = 6

Success:
- Returns `APIResponse.success()`
- Activates user
- Assigns default role `ROLE_USER` if missing
- Clears OTP redis state and clears `pendingToken` cookie

Main error codes:
- `3003 OTP_VERIFICATION_SESSION_EXPIRED`
- `3010 OTP_VERIFY_LIMIT_REACHED` (for `attempt > 5` verify lock)
- `3004 MAX_WRONG_OTP`
- `3005 OTP_EXPIRED`
- `3006 OTP_INVALID`
- `2001 USER_NOT_FOUND`

## 5.3 `POST /resend-otp`
Resend a new OTP for current pending OTP session.

Request body:
- none

Success:
- Returns `APIResponse.success()`
- Rotates `pendingToken` cookie
- Generates new OTP and sends email

Main error codes:
- `3003 OTP_VERIFICATION_SESSION_EXPIRED`
- `3007 COOLDOWN_ACTIVE`
- `2008 OTP_SEND_LIMIT_REACHED`
- `2001 USER_NOT_FOUND`

## 6. Attempt Rules (Important for FE)
- Send limit (`register`, `resend-otp`): blocked when `attempt >= 5`
  - returns `2008 OTP_SEND_LIMIT_REACHED`
- Verify limit (`verify-otp`): blocked when `attempt > 5`
  - returns `3010 OTP_VERIFY_LIMIT_REACHED`

This means:
- User can still verify the last OTP that was already sent at attempt 5.
- User cannot request a new OTP once attempt reaches 5.

## 7. FE Handling Suggestions
- `3003 OTP_VERIFICATION_SESSION_EXPIRED`:
  - clear OTP screen state
  - navigate back to register/start flow
- `3007 COOLDOWN_ACTIVE`:
  - keep user on OTP screen
  - show cooldown message and disable resend temporarily
- `2008 OTP_SEND_LIMIT_REACHED`:
  - disable resend/start a wait-state UI
  - show "send limit reached"
- `3010 OTP_VERIFY_LIMIT_REACHED`:
  - lock verify action for current session and ask user to restart flow
- `3006 OTP_INVALID`:
  - show inline field error on OTP input
- `3005 OTP_EXPIRED`:
  - show expired state and ask user to resend OTP

## 8. Recommended Popup Messages
Use these as default popup/toast messages per error code:

- `3003 OTP_VERIFICATION_SESSION_EXPIRED`
  - Popup title: `Session expired`
  - Popup message: `Your OTP session has expired. Please register again to receive a new OTP.`

- `3007 COOLDOWN_ACTIVE`
  - Popup title: `Please wait`
  - Popup message: `You just requested an OTP. Please wait a moment before resending.`

- `2008 OTP_SEND_LIMIT_REACHED`
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

- `2007 ACCOUNT_BANNED`
  - Popup title: `Account unavailable`
  - Popup message: `An account with this email is banned. Please contact support.`

- `1001/1002/1003/1004/1005/1006` (validation)
  - Popup title: `Invalid input`
  - Popup message: `Please review your input and try again.`
