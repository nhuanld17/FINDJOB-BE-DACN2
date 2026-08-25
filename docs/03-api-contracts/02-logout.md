# FE API Contract - Logout

## 1. Scope
This document covers only:
- `POST /api/v1/auth/logout`

No login/refresh/register/otp is included here.

## 2. Base
- Base path: `/api/v1/auth`
- Content type: `application/json`
- FE must call with credentials included:
  - `fetch`: `credentials: "include"`
  - `axios`: `withCredentials: true`

Reason: server clears HttpOnly cookie `refreshToken` on logout.

## 3. Cookie Contract (`refreshToken`)
Server clears cookie:
- Name: `refreshToken`
- `HttpOnly: true`
- `Secure: true`
- `Path: /`
- `Max-Age: 0`

Important behavior:
- Logout is **idempotent**:
  - If cookie is missing/invalid/expired, server still returns success and still clears cookie.
  - If cookie is present and valid, server revokes refresh session in Redis (`auth:refresh:{userId}` is deleted).

Local dev note:
- Because `Secure=true`, browser only sends cookies over HTTPS.
- If FE/BE are running on plain HTTP, browser may not attach `refreshToken` cookie.
  - Logout will still return success and clear cookie on FE side if cookie exists.
  - Server-side revocation in Redis requires the cookie value to be sent.

## 4. Response Format

### 4.1 Success format
Returns wrapper `APIResponse`:

```json
{
  "code": 1000,
  "message": "Success",
  "data": null
}
```

### 4.2 Error format
Normally logout should not throw business errors. Unexpected server errors can still return:

```json
{
  "status": 500,
  "code": 9999,
  "message": "Internal server error",
  "errors": null,
  "timestamp": "2026-04-25T13:00:00Z"
}
```

## 5. Endpoint Details

## 5.1 `POST /logout`
Clear refresh session and remove refresh token.

> 🔄 **Cập nhật 2026-08-06:** dual-mode — Mobile gửi refresh token trong body, Web gửi qua cookie.

Request (dual-mode, chọn 1 trong 2):
- **Mobile:** body `{ "refreshToken": "..." }`
- **Web:** cookie `refreshToken` (body rỗng)
- Authorization header: not required (endpoint is under `/api/v1/auth/**`)

```json
{
  "refreshToken": "eyJhbGciOiJIUzUxMiJ9..."
}
```

Success:
- HTTP `200`
- Response: `APIResponse.success()`
- Web: Response header `Set-Cookie` clears `refreshToken`
- Mobile: server xoá Redis session tương ứng (không có cookie để clear)

Expected behavior (server-side):
- Always clears `refreshToken` cookie
- If refresh token is present and can be parsed:
  - server finds the user by token subject (email)
  - deletes Redis key `auth:refresh:{userId}` (revokes refresh session)

## 6. FE Handling Suggestions

### 6.1 Standard logout flow
- Call `POST /api/v1/auth/logout` with credentials included.
- Immediately clear FE local auth state:
  - access token in memory/storage
  - user profile cache
  - pending requests queue (if you have refresh-queue logic)
- Redirect to login.

### 6.2 Logout even when session is already expired
If refresh token is already missing/expired:
- Still call logout (safe and idempotent), but you can also skip API call and just clear local state.

### 6.3 Recommended UI behavior
- Always treat logout as success and continue UX flow (do not block user on logout failure toast).
- Optionally log error for debugging if server returns 5xx.

