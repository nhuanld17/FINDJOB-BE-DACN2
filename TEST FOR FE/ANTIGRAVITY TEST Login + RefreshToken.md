---

**Bộ Antigravity Test Chi Tiết: `login` + `refresh-token` (Backend Spring Boot)**

Mục tiêu: chạy **tự động** (không test tay) để đảm bảo 2 chức năng:
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh-token`

Bao phủ đầy đủ case: validation, cookie, rotate refresh token, session expired, inactive flow (OTP), banned, concurrency.

> Lưu ý: repo hiện tại **không có** file/config nào tên “antigravity”, nên phần “antigravity steps” dưới đây được viết theo dạng **spec/DSL trung tính**:
> - `http.request` (gọi API, lưu cookie, lấy response json)
> - `assert.*` (assert status/header/json)
> - `db.sql` (seed DB, đọc DB)
> - `redis.*` (flush/get/set)
>
> Bạn map các step này sang cú pháp antigravity thực tế của bạn (nếu bạn gửi mình 1 ví dụ file antigravity, mình sẽ convert 1:1).

---

## Chuẩn chung

- Base URL: `http://localhost:8080/api/v1/auth`
- Test endpoints:
  - `POST /login`
  - `POST /refresh-token`
- Cookie dùng trong flow:
  - `refreshToken` (HttpOnly, Secure, Path=/, Max-Age=7d)
  - (case inactive login) `pendingToken` (HttpOnly, Secure, Max-Age=10m)
- Wrapper success body kỳ vọng:

```json
{
  "code": 1000,
  "message": "Success",
  "data": {}
}
```

- Wrapper error body kỳ vọng (tối thiểu):

```json
{
  "status": 401,
  "code": 3008,
  "message": "Invalid credentials"
}
```

---

## A. Antigravity Harness Requirements (để test chạy được)

Antigravity runner nên hỗ trợ:
- **Cookie jar per test** (capture `Set-Cookie`, tự attach cookie cho request sau).
- **Send request with credentials** (tương đương browser attach cookie).
- Assert:
  - `status`
  - `jsonPath` / field values
  - header `Set-Cookie` contains/clears
- Optional nhưng rất nên có:
  - `db.sql` để seed/cleanup
  - `redis.flushdb` và `redis.get/set/ttl`
  - chạy **song song** (concurrency tests)

---

## B. Data seeding (DB) cho automation

Vì backend không có “admin API” để tạo user phục vụ test, nên automation nên seed DB trực tiếp.

### B.1 Schema tối thiểu liên quan
- Table `users`: `id`, `email`, `password`, `is_active`, `is_deleted`, `username`, `full_name`, `created_at`, `updated_at`
- Table `roles`: `id`, `name`
- Table `user_role`: `user_id`, `role_id`

### B.2 Seed role
Backend code expect role enum `USER/ADMIN` (và biến ra authority `ROLE_USER/ROLE_ADMIN`).
Automation nên đảm bảo DB có role tương ứng.

**Seed SQL gợi ý (id có thể auto):**

```sql
insert into roles (name) values ('USER') on conflict do nothing;
insert into roles (name) values ('ADMIN') on conflict do nothing;
```

### B.3 Seed users
Vì password được BCrypt encode, antigravity test cần:
- hoặc insert sẵn 1 BCrypt hash known
- hoặc gọi register/verify (nhưng OTP làm test phức tạp hơn)

**BCrypt mẫu** (hash cho plaintext `12345678`) bạn nên generate 1 lần rồi dùng cố định trong test:
- `bcrypt_12345678 = "<PUT_YOUR_BCRYPT_HASH_HERE>"`

---

## C. Biến test (recommended)

- `VALID_PASSWORD = "12345678"`
- `BCRYPT_VALID_PASSWORD = "<PUT_YOUR_BCRYPT_HASH_HERE>"`
- `ACTIVE_USER_EMAIL = "login_active_01@test.com"`
- `INACTIVE_USER_EMAIL = "login_inactive_01@test.com"`
- `BANNED_USER_EMAIL = "login_banned_01@test.com"`
- `UNKNOWN_EMAIL = "login_unknown@test.com"`
- `BASE = "http://localhost:8080/api/v1/auth"`

---

## D. Test `POST /login`

### TC-L01: Login active user (Happy path) — OK
- Pre-condition:
  - Redis sạch cho user này (optional)
  - DB có user:
    - `email=ACTIVE_USER_EMAIL`
    - `is_active=true`
    - `is_deleted=false`
    - `password=BCRYPT_VALID_PASSWORD`
  - DB map role `USER` cho user (`user_role`)
- Steps (antigravity):
  - `http.request`:
    - method: `POST`
    - url: `${BASE}/login`
    - json:
      - email = `ACTIVE_USER_EMAIL`
      - password = `VALID_PASSWORD`
  - `assert.status == 200`
  - `assert.json.code == 1000`
  - `assert.json.data.accessToken` is non-empty string
  - `assert.header.Set-Cookie` contains `refreshToken=`
- Expected side effects:
  - Redis set `auth:refresh:{userId}` = refresh JWT (TTL 7 days)

### TC-L02: Login wrong password — OK
- Pre-condition: same as TC-L01
- Request password sai
- Expected:
  - HTTP `401`
  - `code=3008 INVALID_CREDENTIALS`
  - **không** set `refreshToken` cookie

### TC-L03: Login unknown email — OK
- Pre-condition: DB không có user với email này
- Expected:
  - HTTP `401` hoặc `404` tùy cách map exception hiện tại
  - Nếu backend đi theo `AuthenticationManager` -> thường `401` với `code=3008` hoặc `3001`
  - **Không** set `refreshToken`
> Ghi chú: hiện code có `USER_NOT_FOUND(2001)` nhưng login flow chủ yếu ném `BadCredentialsException`.

### TC-L04: Login validation fail (email blank) — OK
- Request:
  - `email=""`, password valid
- Expected:
  - HTTP `400`
  - `ErrorResponse.message="Validation failed"`
  - `errors.email` contains message resolved từ `INVALID_EMAIL/BLANK_FIELD`

### TC-L05: Login validation fail (invalid email format) — OK
- Request `email="abc"`
- Expected: HTTP `400`, validation errors đúng field `email`

### TC-L06: Login validation fail (password too short) — OK
- Request `password="123"`
- Expected: HTTP `400`, errors.password contains `Password must be at least {min} characters`

### TC-L07: Login inactive user (should trigger OTP flow) — OK
- Pre-condition:
  - DB có user:
    - `email=INACTIVE_USER_EMAIL`
    - `is_active=false`
    - `is_deleted=false`
    - `password=BCRYPT_VALID_PASSWORD`
- Steps:
  - call `POST /login` with correct creds
- Expected response:
  - HTTP `403`
  - `code=2005 USER_INACTIVE`
  - Optional: `Set-Cookie` contains `pendingToken=` (backend may set/renew)
  - Must: **không** set `refreshToken` (user chưa active)
- Expected side effects (OTP state):
  - Nếu attempts < 5 và cooldown hết: tạo OTP + set cooldown/attempts/wrong + pending token
  - Nếu cooldown còn: reuse/renew pending token, không tạo OTP mới
  - Nếu attempts >= 5: trả `2008 OTP_SEND_LIMIT_REACHED` (xem TC-L08)

### TC-L08: Login inactive user but OTP send limit reached — OK
- Pre-condition:
  - user inactive như TC-L07
  - Redis `otp:attempts:{userId}=5`
- Steps:
  - call `POST /login` with correct creds
- Expected:
  - HTTP `429`
  - `code=2008 OTP_SEND_LIMIT_REACHED`
  - no `refreshToken` cookie

### TC-L09: Login banned user — OK
- Pre-condition:
  - DB có user:
    - `email=BANNED_USER_EMAIL`
    - `is_active=true` (hoặc false cũng được)
    - `is_deleted=true`
    - `password=BCRYPT_VALID_PASSWORD`
- Expected:
  - HTTP `403`
  - `code=2007 ACCOUNT_BANNED`
  - no cookies set

### TC-L10: Login should lowercase email — OK
- Pre-condition: user active, email stored lowercase
- Request: send email uppercase/mixed case
- Expected:
  - login success (TC-L01)
  - accessToken returned

### TC-L11: Login twice (refresh token should be replaced) — OK
- Pre-condition: user active
- Steps:
  - call login lần 1 -> capture cookie jar (`refreshToken_1`)
  - call login lần 2 -> capture cookie jar (`refreshToken_2`)
- Expected:
  - both success
  - cookie `refreshToken` changes (value should differ)
  - Redis `auth:refresh:{userId}` matches latest refresh token

---

## E. Test `POST /refresh-token`

### TC-RF01: Refresh success (valid cookie) — OK
- Pre-condition:
  - user active
  - login success before this test to obtain `refreshToken` cookie
- Steps:
  - `http.request` POST `${BASE}/refresh-token` with cookie jar
- Expected:
  - HTTP `200`
  - `code=1000`
  - `data.accessToken` non-empty
  - `Set-Cookie` contains new `refreshToken=` (rotate)

### TC-RF02: Refresh without cookie — OK
- Steps: call refresh with empty cookie jar
- Expected:
  - HTTP `401`
  - `code=3009 UNAUTHORIZED`
  - `Set-Cookie` clears refreshToken (optional but recommended by backend)

### TC-RF03: Refresh with malformed cookie value — OK
- Pre-condition: set cookie `refreshToken="abc"`
- Expected:
  - HTTP `401`
  - `code=3009`
  - cookie cleared

### TC-RF04: Refresh token valid JWT but NOT matching Redis (rotated/reused) — OK
- Pre-condition:
  - login -> receive refreshToken_1
  - refresh success -> rotates to refreshToken_2 (cookie jar now has _2; Redis has _2)
- Steps:
  - Create a new request that **forces** sending old `refreshToken_1`
  - call refresh-token
- Expected:
  - HTTP `401`
  - `code=3009`
  - cookie cleared

### TC-RF05: Refresh for banned user — OK
- Pre-condition:
  - Obtain a refresh token cookie for a user
  - Then mark user `is_deleted=true` in DB
- Steps: call refresh-token with existing cookie
- Expected:
  - HTTP `403`
  - `code=2007 ACCOUNT_BANNED`
  - refresh cookie cleared

### TC-RF06: Refresh for inactive user — OK
- Pre-condition:
  - Obtain refresh cookie for user (login khi active)
  - Then flip DB `is_active=false`
- Expected:
  - HTTP `403`
  - `code=2005 USER_INACTIVE`
  - refresh cookie cleared

### TC-RF07: Refresh concurrency (2 requests at same time) — OK
Mục tiêu: đảm bảo rotate refresh không gây “random 500”.
- Pre-condition: have valid cookie jar from login
- Steps:
  - bắn 2 request `POST /refresh-token` song song dùng cùng cookie jar
- Expected:
  - 1 request success (rotates token)
  - request còn lại:
    - có thể fail `3009` vì refreshToken đã rotate
    - nhưng **không** được 500

---

## F. End-to-End Scenarios (khuyến nghị chạy nightly)

### TC-E2E01: Login -> call protected API -> refresh -> retry
- Flow:
  - login success, lấy accessToken
  - gọi 1 protected endpoint (bất kỳ) với Bearer token
  - simulate access token expired (nếu antigravity có khả năng time travel / override token exp)
  - call refresh-token to get new accessToken
  - retry protected API ok
> Nếu repo chưa có endpoint protected “dễ test”, bạn có thể chỉ test refresh-token + jwt filter ở level integration sau.

### TC-E2E02: Login inactive -> OTP verify -> login success
- Flow:
  - seed user inactive
  - login -> expect `USER_INACTIVE` (+ pendingToken)
  - gọi OTP verify (phụ thuộc suite OTP)
  - login lại -> success + refresh cookie

---

## G. Checklist Assert Chung

- HTTP status đúng
- Business code đúng (`ErrorCode`)
- Cookie:
  - login success: set `refreshToken`
  - refresh success: rotate `refreshToken`
  - refresh fail: clear cookie (nếu backend làm)
  - login inactive: có thể set `pendingToken`, không set `refreshToken`
- Body:
  - success có `data.accessToken`
  - error có `status`, `code`, `message`
- Redis:
  - login success: `auth:refresh:{userId}` tồn tại và update theo lần login mới nhất
  - refresh success: Redis refresh token được rotate

---

## H. Known gaps / Notes

1) Để test automation “chuẩn tuyệt đối”, antigravity nên có bước `db.query` và `redis.get` để assert side effects.
2) Nếu environment chạy HTTP (không HTTPS), cookie `Secure=true` có thể không attach trong browser; với antigravity runner thì tuỳ implementation.
3) Nếu bạn gửi 1 file antigravity mẫu (1 test bất kỳ), mình sẽ rewrite toàn bộ suite này sang **đúng syntax antigravity** (không còn pseudo).

