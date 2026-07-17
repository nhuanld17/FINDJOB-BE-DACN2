# Tại sao backend vẫn có thể redirect về React SPA mặc dù frontend chỉ gọi một lần?

Có 2 câu hỏi lớn xoay quanh redirect trong OAuth2:

> **Q1:** Làm sao backend (`localhost:8080/oauth2/authorization/google`) có thể đưa mình đến trang login của **Google** (`accounts.google.com`) được?
>
> **Q2:** Sau khi Google xác thực xong, làm sao backend có thể redirect mình **quay về React SPA** (`localhost:5173`) được — dù React chỉ gọi 1 lần duy nhất là `window.location.href`?

Tài liệu này sẽ trả lời cả 2 câu hỏi.

---

## 🧩 Câu trả lời ngắn — cho ai muốn đáp án nhanh

| Câu hỏi | Đáp án |
|---------|--------|
| **Q1:** `/oauth2/authorization/google` → Google login page? | Backend trả về **HTTP 302** với `Location: https://accounts.google.com/...` → browser tự động đi hướng đến đó |
| **Q2:** Backend redirect về `localhost:5173` sau khi Google xong? | Cũng là **HTTP 302**. `OidcLoginSuccessHandler` gọi `response.sendRedirect()` trong response của request callback từ Google |

Cả 2 đều dùng cùng 1 cơ chế: **HTTP Redirect (302)**. Không có ma thuật gì cả.

---

## ❓ Mở rộng câu hỏi: Tại sao thấy khó hiểu?

Bạn đã quen với kiểu "gọi API" thông thường:

```
Browser ── fetch() ──→ Backend API
   ▲                      │
   └───── JSON ───────────┘
```

Trong OAuth2, browser không dùng `fetch()`. Nó điều hướng **toàn bộ trang**, và backend không trả JSON — nó trả **302 redirect**. Bảng so sánh:

| | API call thường (`fetch`) | OAuth2 navigation (`window.location.href`) |
|---|---|---|
| Cách gửi | `fetch()` | `window.location.href = ...` |
| React App | Vẫn chạy (async) | **Biến mất** (browser tải trang mới) |
| Backend trả về | JSON | **302 Redirect** |
| Kết quả | JS xử lý JSON | Browser tự động điều hướng |

---

## 🧠 Phân tích Q1: Làm sao localhost:8080 ra được Google?

Đây là bước đầu tiên của OAuth2 Authorization Code Flow.

### Trước hết: Backend KHÔNG "gọi" Google

Bạn tưởng tượng backend kiểu như đang gọi API sang Google rồi lấy trang login về cho mình? **Không phải.**

Backend chỉ làm 1 việc duy nhất: **build URL và bảo browser tự đi.**

### Cụ thể:

**Step 1:** Browser gửi request:
```
GET http://localhost:8080/oauth2/authorization/google
```

**Step 2:** Spring Security (`OAuth2AuthorizationRequestRedirectFilter`) nhận request, đọc config trong `application.yml`:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: 1234567890-xxxxx.apps.googleusercontent.com
            scope: openid, email, profile
            redirect-uri: "{baseUrl}/login/oauth2/code/google"
```

**Step 3:** Spring Security build ra authorization URL:

```
https://accounts.google.com/o/oauth2/v2/auth?
  response_type=code&
  client_id=1234567890-xxxxx.apps.googleusercontent.com&
  scope=openid%20email%20profile&
  redirect_uri=http://localhost:8080/login/oauth2/code/google&
  state=abc123...&
  nonce=xyz...
```

Đây là URL **chuẩn OAuth2**. Bạn có thể copy paste URL này ra browser và nó cũng hoạt động y hệt.

**Step 4:** Backend trả về HTTP response:

```
HTTP/1.1 302 Found
Location: https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=...
```

**Step 5:** Browser thấy status 302 + header `Location` → **tự động điều hướng đến URL đó** → Google login page hiện ra.

> 💡 **Mẹo:** Mở Network tab DevTools, bạn sẽ thấy:
> ```
> Request:  localhost:8080/oauth2/authorization/google
> Status:   302
> Headers:  Location: https://accounts.google.com/...
> ```
> Sau đó browser tự động gửi request đến `accounts.google.com/...`

---

## 🧠 Phân tích Q2: Làm sao backend redirect về FE sau khi Google xong?

Thoạt nhìn có vẻ khó hiểu.

> **Câu hỏi:** React chỉ khởi tạo OAuth2 bằng window.location.href, sau đó gần như không còn tham gia vào quá trình đăng nhập. Vậy tại sao backend vẫn có thể redirect trình duyệt quay trở lại React SPA?

---

## 🧠 Bản chất: FE không dùng `fetch`/`axios` để gọi OAuth2

Đây là điểm mấu chốt mà nhiều người nhầm lẫn. Sự khác biệt nằm ở **cách** FE gửi request:

### ❌ Cách thường — Login bằng username/password

```javascript
// FE dùng fetch → async request
const res = await fetch('/api/v1/auth/login', {
  method: 'POST',
  body: JSON.stringify({ username, password })
});
// ❌ Kết thúc ở đây
// FE nhận response JSON, không có redirect gì cả
```

### ✅ Cách OIDC — Login bằng Google

FE **không dùng fetch**. FE chỉ làm một việc duy nhất:

```javascript
// Cực kỳ đơn giản:
window.location.href = "http://localhost:8080/oauth2/authorization/google";
```

Đây là **full browser navigation** — giống hệt như user gõ URL vào thanh địa chỉ và nhấn Enter. Trình duyệt **điều hướng hoàn toàn khỏi React App**.

```
🚶 Browser điều hướng:

[React App] ──── window.location.href ────→ [Backend]
  (biến mất)                                  │
                                               ↓
                                        Google login page
                                               │
                                         User đăng nhập
                                               │
                                               ↓
                                     [Backend nhận callback]
                                               │
                                          Redirect về FE
                                               │
                                               ↓
[React App] ←── tải lại từ đầu, URL có ?ticket=xxx
```

---

## 🔬 Phân tích chi tiết: Backend redirect bằng cách nào?

Suốt quá trình từ bước 1 đến bước 9, **browser và backend nói chuyện trực tiếp với nhau qua HTTP**, không có React App ở giữa.

Sơ đồ HTTP đầy đủ:

```
 BROWSER (tab hiện tại)              BACKEND (localhost:8080)
       │                                  │
       │  Request #1                       │
       ├── GET /oauth2/authorization/google ──→│
       │                                  │
       │  Response #1 (302)               │
       │←─ 302 Location: accounts.google.com ──┤
       │                                  │
       │  ► Browser tự động redirect đến Google
       │                                  │
       │  (User login + consent)          │
       │                                  │
       │  Google redirect về callback     │
       │                                  │
       │  Request #2                      │
       ├── GET /login/oauth2/code/google?code=xyz ──→│
       │                                  │
       │  → CustomOidcUserService.loadUser()
       │  → OidcLoginSuccessHandler
       │  → Tạo ticket, lưu Redis
       │                                  │
       │  Response #2 (302)               │
       │←─ 302 Location: http://localhost:5173/oauth-callback?ticket=abc ──┤
       │                                  │
       │  ► Browser tự động redirect về localhost:5173
       │                                  │
       │  (React App được tải lại từ đầu) │
       │                                  │
       │  Request #3                      │
       ├── POST /api/v1/auth/exchange-ticket ──→│
       │    (fetch/axios — lần đầu FE gọi API)
       │                                  │
       │  Response #3 (200 JSON)          │
       │←─ { accessToken, ... } ──────────────┤
```

### 📌 Điểm mấu chốt

| Request # | Ai gửi? | Giao thức | Backend trả về |
|-----------|---------|-----------|----------------|
| 1 | **Browser** (do `window.location.href`) | Redirect thường | HTTP 302 → Google |
| 2 | **Browser** (do Google redirect về) | Redirect thường | HTTP 302 → `localhost:5173?ticket=xxx` |
| 3 | **React App** (dùng `fetch`) | API call | HTTP 200 + JSON |

> Trong Request #2, backend (cụ thể là `OidcLoginSuccessHandler`) gọi:
> ```java
> response.sendRedirect("http://localhost:5173/oauth-callback?ticket=" + ticket);
> ```
> **Có quyền gì mà gọi `sendRedirect`?** Vì đây là response của **chính request callback từ Google** mà browser đang đợi. Backend set HTTP Status = 302 với header `Location` → browser tự động follow redirect.

---

## 🔄 Tóm tắt bằng một câu

> **Không phải FE gọi API rồi hết — FE tự biến mất bằng cách điều hướng trình duyệt hoàn toàn, và backend giữ response gốc để redirect browser quay lại FE khi xong.**

---

## 📊 So sánh: `fetch` vs `window.location.href`

| | `fetch()` / `axios` | `window.location.href` |
|---|---|---|
| **Loại request** | AJAX — chạy trong nền (background) | Full browser navigation — tab thực sự điều hướng |
| **React App** | Vẫn chạy, chờ response dưới dạng dữ liệu | **Biến mất**, browser tải trang mới từ đầu |
| **Backend trả về JSON** | ✅ FE nhận và xử lý bằng JS | ❌ Browser không biết xử lý JSON, hiển thị raw text |
| **Backend trả về 302** | ⚠️ **Có follow redirect, nhưng trong nền.** Browser không điều hướng, user không thấy trang đích. Google trả về HTML → fetch trả HTML string cho React → rác | ✅ **Browser tự động điều hướng tab đến URL mới. User thấy Google login page.** |
| **Cookie từ response** | Set cookie nhưng không reload trang | ✅ Set cookie + trang được load lại tự nhiên |
| **Khi nào dùng** | API thông thường (login, CRUD) | OAuth2 flow cần redirect browser đi nơi khác |

---

## 🎯 Kết luận

1. **OIDC flow không phải là API call** — nó là chuỗi redirect qua lại giữa browser và các server
2. **FE chỉ tham gia 2 lần:**
   - **Đầu:** 1 dòng `window.location.href` để kick-start flow
   - **Cuối:** 1 `fetch` để exchange-ticket sau khi nhận được ticket từ URL
3. **Mọi thứ ở giữa** (Google login, callback, tạo ticket, redirect) **xảy ra hoàn toàn giữa browser và backend** — React App không hề tồn tại trong khoảng thời gian đó
4. **Backend redirect được** vì nó đang giữ **response gốc của request mà browser đang đợi** — không phải response của API call từ fetch

---

## 🧪 Trực quan hóa bằng DevTools

Để thấy rõ điều này, mở **Network tab** trong Chrome DevTools trước khi click "Login with Google":

1. Bắt đầu record Network
2. Click "Login with Google"
3. Quan sát:

```
📡 Network log sẽ thấy:

http://localhost:8080/oauth2/authorization/google  ← 302 (browser redirect)
https://accounts.google.com/...                     ← 200 (Google login page)
...                                                 ← (user login)
http://localhost:8080/login/oauth2/code/google?code=... ← 302 (callback)
http://localhost:5173/oauth-callback?ticket=xxx     ← 200 (React App loaded lại)
fetch → http://localhost:8080/api/v1/auth/exchange-ticket ← 200 JSON
```

> **Không thấy bất kỳ `fetch` API nào gọi đến `/oauth2/authorization/google`** — vì nó là full navigation, không phải AJAX call.

---

> **Xem thêm:** [1. OIDC Login Flow.md](./1.%20OIDC%20Login%20Flow.md) — luồng chi tiết từng bước
