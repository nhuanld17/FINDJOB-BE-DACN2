# Mobile OIDC Flow — Đăng nhập Google trên React Native

> **Mục đích:** Tài liệu này giải thích cách **Mobile App (React Native)** thực hiện đăng nhập bằng Google, khác với web (React SPA) ở những điểm nào, và cách backend xử lý cả 2 platform qua cùng 1 OAuth2 pipeline.

---

## 🌐 Tổng quan

### Web vs Mobile — Khác biệt cơ bản

| | **Web (SPA)** | **Mobile (React Native)** |
|---|---|---|
| Mở Google login | `window.location.href` — **rời hẳn app** | `WebBrowser.openAuthSessionAsync()` — **mở system browser overlay** |
| Callback | Browser redirect về `localhost:5173` | System browser redirect về **deep link** `findjob://` |
| Cookie | Trình duyệt tự động quản lý (kể cả `oauth2_state` + cookie Google) | App RN **không có Cookie Jar** → app không lưu cookie. ⚠️ NHƯNG **In-App Browser (system browser overlay) CÓ nhận & lưu** cookie `oauth2_state` (chống CSRF) + cookie Google trong lúc chạy flow — xem mục Bảo mật #4 |
| Nhận ticket | Đọc từ URL trên browser tab | `Linking.parse()` sau khi system browser đóng |
| Gửi lại ticket | Cookie hoặc fetch | Header `X-Pending-Token` hoặc body |

### Luồng tổng thể

```
📱 Mobile App (Expo/RN)          🖥️ Backend (Spring Boot)          ☁️ Google
         │                                │                             │
         │  1. openAuthSessionAsync()     │                             │
         │──── GET /oauth2/authorization/ │                             │
         │    google?return_url=findjob://│                             │
         │   ────────────────────────────►│                             │
         │                                │  2. Lưu return_url vào Redis│
         │                                │    (keyed by OAuth state)   │
         │                                │                             │
         │                                │   3. 302 → Google login     │
         │   (System browser mở)          │◄────────────────────────────│
         │◄── 302 redirect ───────────────│                             │
         │                                │                             │
         │  4. User login + consent       │                             │
         │─────────────────────────────────────────────────────────────►│
         │                                │                             │
         │  5. Google callback            │                             │
         │                                │◄────────────────────────────│
         │                                │                             │
         │  6. Exchange code → token      │                             │
         │                                │────────────────────────────►│
         │                                │◄──── ID Token + UserInfo ──│
         │                                │                             │
         │  7. CustomOidcUserService      │                             │
         │    (find/create user)          │                             │
         │                                │                             │
         │  8. OidcLoginSuccessHandler    │                             │
         │    - Tạo one-time ticket       │                             │
         │    - Đọc return_url từ Redis   │                             │
         │                                │                             │
         │  9. System browser redirect    │                             │
         │◄── findjob://oauth/callback?   │                             │
         │    ticket=xxx ─────────────────│                             │
         │                                │                             │
         │  10. App nhận được ticket      │                             │
         │    (Linking.parse)             │                             │
         │                                │                             │
         │  11. POST /exchange-ticket     │                             │
         │───────────────────────────────►│                             │
         │                                │  12. Atomic GETDEL Redis    │
         │                                │  13. createUserSession()    │
         │◄──── AuthResponse (JWT) ───────│                             │
         │                                │                             │
         │  14. Lưu JWT vào SecureStore   │                             │
         │  15. navigate('Home')          │                             │
```

---

## 🧩 Các thành phần tham gia

### 1. `LoginScreen.tsx` / `RegisterScreen.tsx` (Mobile Frontend)

**Vai trò:** Giao diện người dùng + khởi tạo OAuth flow

**Code chính:**

```typescript
const handleGoogleLogin = async () => {
  const apiUrl = process.env.EXPO_PUBLIC_API_URL || 'http://localhost:8080';
  const redirectUrl = Linking.createURL('oauth/callback');  // "findjob://oauth/callback"
  const authUrl = `${apiUrl}/oauth2/authorization/google?return_url=${encodeURIComponent(redirectUrl)}`;
  //                                                         ^^^^^^^^^^
  //                                          Mobile đính kèm return_url để backend biết
  //                                          redirect về deep link thay vì web URL

  const result = await WebBrowser.openAuthSessionAsync(authUrl, redirectUrl);

  if (result.type === 'success' && result.url) {
    const { queryParams } = Linking.parse(result.url);
    const ticket = queryParams?.ticket as string | undefined;

    if (ticket) {
      await exchangeTicket(ticket);           // POST /api/v1/auth/exchange-ticket
      navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
    }
  }
};
```

### 2. `expo-web-browser` — `WebBrowser.openAuthSessionAsync()`

**Vai trò:** Mở system browser (Safari/Chrome Custom Tab) trong overlay, không rời hẳn app.

**Khác với web:**
- Web: `window.location.href` → **rời hẳn app**, redirect qua nhiều trang
- Mobile: `openAuthSessionAsync()` → mở **SafariViewController / Chrome Custom Tab** ở chế độ overlay, app React Native vẫn chạy ngầm

```mermaid
┌──────────────────────┐
│   React Native App   │
│                      │
│  ┌──────────────────┐│
│  │  System Browser  ││
│  │  (overlay)       ││
│  │                  ││
│  │  accounts.google ││
│  │  .com/...        ││
│  │                  ││
│  └──────────────────┘│
│                      │
│  [App vẫn chạy ngầm] │
└──────────────────────┘
```

**Kết quả trả về:**
- `{ type: 'success', url: 'findjob://oauth/callback?ticket=xxx' }` — user login thành công
- `{ type: 'cancel' }` — user tự đóng browser
- `{ type: 'dismiss' }` — user vuốt đóng browser

### 3. `expo-linking` — `Linking.createURL()` / `Linking.parse()`

**Vai trờ:** Tạo deep link URL và parse URL callback.

- `Linking.createURL('oauth/callback')` → `"findjob://oauth/callback"` (dựa vào scheme trong `app.json`)
- `Linking.parse('findjob://oauth/callback?ticket=xxx')` → `{ queryParams: { ticket: 'xxx' } }`

### 4. `app.json` — Deep link configuration

```json
{
  "expo": {
    "scheme": "findjob",
    "android": {
      "intentFilters": [
        {
          "action": "VIEW",
          "data": [{ "scheme": "findjob", "host": "callback", "pathPrefix": "/oauth" }],
          "category": ["BROWSABLE", "DEFAULT"]
        }
      ]
    }
  }
}
```

Khi system browser redirect về `findjob://oauth/callback?ticket=xxx`, Android/iOS mở lại app thông qua intent filter này.

---

## 🔄 Chi tiết từng bước

### Bước 1: App gọi `openAuthSessionAsync()`

```
authUrl = "http://localhost:8080/oauth2/authorization/google?return_url=findjob%3A%2F%2Foauth%2Fcallback"
redirectUrl = "findjob://oauth/callback"
```

**Tại sao có `return_url`?**
- Web: backend biết redirect về `http://localhost:5173/oauth-callback` (cấu hình cứng)
- Mobile: backend **không biết** deep link của app là gì → mobile tự truyền qua query param

### Bước 2: `RedisOAuth2AuthorizationRequestRepository` lưu return_url

```java
// RedisOAuth2AuthorizationRequestRepository.java (dòng 106-116)
String returnUrl = request.getParameter("return_url");
if (returnUrl != null && !returnUrl.isBlank() && isAllowedMobileScheme(returnUrl)) {
    stringRedisTemplate.opsForValue().set(
            Oauth2Constant.RETURN_PREFIX + authorizationRequest.getState(),  // "oauth2:return:<state>" (prefix trong common/constant/Oauth2Constant)
            returnUrl,
            STATE_TTL_SECONDS,     // 120s
            TimeUnit.SECONDS
    );
}
```

**Security:**
- `isAllowedMobileScheme()`: chỉ chấp nhận scheme trong whitelist (VD: `findjob://`)
- Chống **open redirect attack**: không cho redirect về URL tùy ý

### Bước 3-6: Giống web flow

Giống hệt web: redirect Google → login → callback → exchange code → lấy user info

### Bước 7: Backend điều hướng khác nhau dựa vào `return_url`

**Web:** Redirect về URL mặc định:
```java
frontendRedirectUrl = "http://localhost:5173/oauth-callback";
response.sendRedirect(frontendRedirectUrl + "?ticket=" + ticket);
```

**Mobile:** Redirect về deep link đã lưu:
```java
// OidcLoginSuccessHandler.java (dòng 58-76)
String state = request.getParameter("state");
String returnUrl = null;
if (state != null) {
    returnUrl = stringRedisTemplate.opsForValue()
            .getAndDelete(Oauth2Constant.RETURN_PREFIX + state);  // Atomic GETDEL
}

String target = (returnUrl != null && isAllowedMobileScheme(returnUrl))
        ? returnUrl           // ← Mobile: redirect về deep link
        : frontendRedirectUrl; // ← Web: redirect về SPA

String redirectUrl = target + "?ticket=" + ticket;
response.sendRedirect(redirectUrl);
```

**Mobile callback:**
```
findjob://oauth/callback?ticket=abc-123
```

**Khi lỗi (OidcLoginFailureHandler):**
- Web: trả JSON error
- Mobile: redirect về deep link kèm `?error=errorCode`:
```
findjob://oauth/callback?error=2007
```

### Bước 8: App nhận callback

```typescript
if (result.type === 'success' && result.url) {
    const { queryParams } = Linking.parse(result.url);
    
    // Thành công
    if (queryParams?.ticket) {
        await exchangeTicket(queryParams.ticket);
        navigation.reset({ index: 0, routes: [{ name: 'Home' }] });
    }
    
    // Lỗi
    if (queryParams?.error) {
        setError(`Đăng nhập Google thất bại (mã ${queryParams.error})`);
    }
}

if (result.type === 'cancel') {
    // User tự đóng browser → bỏ qua, không báo lỗi
}
```

---

## 🆚 So sánh: Web vs Mobile trong OAuth2 flow

### Góc nhìn browser navigation

```
WEB:                                 MOBILE:

[SPA]                                [App]
  │                                     │
  ├── window.location.href ──────►      ├── openAuthSessionAsync() ──────►
  │   (React App biến mất)             │   (System browser overlay)
  │                                     │
  ▼                                     ▼
Google login page              Google login page (trong overlay)
  │                                     │
  ▼                                     │
Backend callback                Backend callback (trong overlay)
  │                                     │
  ▼                                     │
302 về localhost:5173?ticket=xxx        302 về findjob://callback?ticket=xxx
  │                                     │
  ▼                                     │
React load lại từ đầu           System browser tự đóng
  │                                     │
  ├── fetch /exchange-ticket ──────►    ├── Linking.parse(url) → get ticket
  │                                     ├── fetch /exchange-ticket
  ▼                                     ▼
Login thành công                 Login thành công
  (reload trang)                  (navigate về Home, ko reload)
```

### Góc nhìn state management

| Yếu tố | Web | Mobile |
|---|---|---|
| **Trạng thái app khi OAuth** | App biến mất, redirect qua nhiều trang | App vẫn chạy ngầm, system browser overlay |
| **Nhận ticket** | Browser URL trên tab mới | `result.url` từ `openAuthSessionAsync` |
| **Lưu JWT** | localStorage + cookie HttpOnly | `expo-secure-store` (iOS Keychain) |
| **Gửi refresh token** | Cookie tự động | Body `{ refreshToken }` |
| **Gửi pendingToken** | Cookie tự động | Header `X-Pending-Token` |
| **Xử lý lỗi** | Server trả JSON → FE render | Server redirect về deep link → `Linking.parse` |

---

## 🔐 Chi tiết bảo mật cho mobile

### 1. Open redirect protection

Backend kiểm tra `return_url` có nằm trong whitelist không (cấu hình qua `app.oauth2.allowed-mobile-schemes`). Mặc định chỉ chấp nhận scheme `findjob://`.

```yaml
# application.yml
app:
  oauth2:
    allowed-mobile-schemes: findjob://
```

### 2. One-time ticket (giống web)

Ticket chỉ dùng được 1 lần (atomic GETDEL Redis). Nếu bị lộ, attacker chỉ dùng được 1 lần.

### 3. Ticket TTL = 60s (giống web)

Ticket tự hủy sau 60 giây.

### 4. State param chống CSRF

OAuth2 `state` param được lưu trong Redis + cookie `oauth2_state` để xác thực callback.

**Cookie `oauth2_state` — In-App Browser chính là người giữ cookie:**

1. App gọi `GET /oauth2/authorization/google?return_url=...` → backend tạo `OAuth2AuthorizationRequest` kèm **`Set-Cookie: oauth2_state=<stateId>`** (TTL 120s, path `/`, SameSite=Lax) → **In-App Browser nhận và lưu cookie này** (xem `RedisOAuth2AuthorizationRequestRepository` — `COOKIE_NAME = "oauth2_state"`)
2. Google redirect về `/login/oauth2/code/google?...` → **In-App Browser tự động gửi lại cookie** lên → Spring Security đọc cookie, đối chiếu `stateId` với Redis → xác thực callback hợp lệ
3. Cookie `oauth2_state` **chỉ sống trong system browser** — app RN không đọc, không lưu. Hết TTL 120s hoặc khi flow hoàn tất → cookie hết tác dụng

> ⚠️ **Giải thích chỗ dễ nhầm:** câu "React Native không dùng cookie" trong bảng so sánh nói về **app RN** (JWT lưu SecureStore, refresh token gửi qua body). Còn **In-App Browser thì vẫn nhận & lưu cookie bình thường** — vì nó là một trình duyệt thật (SafariViewController / Chrome Custom Tab), không phải là "không có cookie" cho cả luồng.

### 5. Lưu token an toàn

Mobile dùng `expo-secure-store` — tương đương iOS Keychain / Android Keystore. Token được mã hóa ở OS level.

---

## ⚙️ Cấu hình liên quan

### `application.yml`
```yaml
app:
  oauth2:
    redirect-url: http://localhost:5173/oauth-callback       # Web mặc định
    allowed-mobile-schemes: findjob://                        # Mobile whitelist
```

### `app.json` (React Native)
```json
{
  "expo": {
    "scheme": "findjob",
    "android": {
      "intentFilters": [{
        "action": "VIEW",
        "data": [{ "scheme": "findjob", "host": "callback", "pathPrefix": "/oauth" }],
        "category": ["BROWSABLE", "DEFAULT"]
      }]
    },
    "ios": {
      "bundleIdentifier": "com.findjob.app"
    }
  }
}
```

### Redis keys thêm cho mobile
| Key pattern | Value | TTL |
|---|---|---|
| `oauth2:return:<OAuth state>` | `findjob://oauth/callback` | 120s |

> `OAuth state` ≠ `stateId` (xem `RedisOAuth2AuthorizationRequestRepository` để hiểu rõ sự khác biệt).

---

## 🧪 Test flow (bằng HTTP file)

```http
### Bước 1: Mở deep link này trên mobile (hoặc browser)
### (Không thể test qua HTTP file, cần app mobile thật)
### http://localhost:8080/oauth2/authorization/google?return_url=findjob%3A%2F%2Foauth%2Fcallback&...

### Bước 2: Exchange ticket (giống web)
### Copy ticket từ URL callback rồi paste vào đây
POST http://localhost:8080/api/v1/auth/exchange-ticket
Content-Type: application/json

{
  "ticket": "PASTE_TICKET_VAO_DAY"
}
```

---

## 📚 Liên quan

- [01. OIDC Login Flow.md](./01-login-flow.md) — luồng chi tiết backend (dùng chung cho web + mobile)
- [02. Why Backend Redirect.md](./02-why-backend-redirect.md) — giải thích cơ chế redirect
- `RedisOAuth2AuthorizationRequestRepository.java` — lưu return_url của mobile
- `OidcLoginSuccessHandler.java` — phân luồng web vs mobile khi redirect
- `OidcLoginFailureHandler.java` — xử lý lỗi cho mobile (redirect về deep link)
- `LoginScreen.tsx` / `RegisterScreen.tsx` — mobile UI + OAuth init
