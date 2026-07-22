# 📚 FINDJOB-BE Documentation

> Tài liệu kỹ thuật cho Backend Spring Boot của ứng dụng FINDJOB

---

## 🔐 Authentication (`01-auth/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Register & OTP Flow](01-auth/01-register-and-otp.md) | Đăng ký tài khoản + OTP verification (4 đồng hồ OTP, decision tree) |
| 2 | [Login](01-auth/02-login.md) | Đăng nhập email/password (2 luồng: ACTIVE / INACTIVE) |
| 3 | [Session & Token Architecture](01-auth/03-session-and-token.md) | Kiến trúc JWT + Redis session, triết lý "session là nguồn sự thật" |
| 4 | [Refresh Token](01-auth/04-refresh-token.md) | Refresh token rotation + reuse detection |
| 5 | [Logout](01-auth/05-logout.md) | Đăng xuất (revoke session + blacklist token) |
| 6 | [JwtAuthFilter](01-auth/06-jwt-auth-filter.md) | Filter xác thực request (JWT + session check) |
| 7 | [Access & Refresh Token Flow](01-auth/07-access-refresh-flow.md) | Luồng tổng hợp AT & RT |
| 8 | [Token Revocation](01-auth/08-token-revocation.md) | Thu hồi token (blacklist) |

## 🔑 OIDC — Google Login (`02-oidc/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [OIDC Login Flow](02-oidc/01-login-flow.md) | Google OIDC login flow (one-time ticket redirect) |
| 2 | [Why Backend Redirect?](02-oidc/02-why-backend-redirect.md) | Giải thích tại sao OAuth2 callback cần redirect về FE |

## 📋 API Contracts (`03-api-contracts/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Login & Refresh Token](03-api-contracts/01-login-refresh-token.md) | API contract cho login + refresh token |
| 2 | [Logout](03-api-contracts/02-logout.md) | API contract cho logout |
| 3 | [Register & OTP](03-api-contracts/03-register-otp.md) | API contract cho register + OTP |

## 🧪 Test Cases (`04-test-cases/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Authentication Test Cases](04-test-cases/01-authentication.md) | Test cases cho Authentication module (OTP Flow) |
| 2 | [Access Token & Refresh Token Test](04-test-cases/02-access-refresh-token.md) | Test cases cho AT & RT |
| 3 | [Manual Test Template](04-test-cases/03-manual-test-template.md) | Template cho manual testing |

## 📝 Development Plans (`05-plans/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Integrate OIDC](05-plans/01-integrate-oidc.md) | Plan tích hợp Google OIDC (3 phases) |

## 📓 Notes (`notes/`)

| File | Mô tả |
|------|-------|
| [Sonar Setup](notes/sonar-setup.txt) | Hướng dẫn chạy SonarQube local |

---

## 🗺️ Sơ đồ kiến trúc docs

```
docs/
├── README.md                          ← Bạn đang ở đây
├── 01-auth/                           ← 🔐 Authentication
├── 02-oidc/                           ← 🔑 OIDC (Google Login)
├── 03-api-contracts/                  ← 📋 API contracts
├── 04-test-cases/                     ← 🧪 Test cases
├── 05-plans/                          ← 📝 Development plans
└── notes/                             ← 📓 Notes
```

> 📌 **Lưu ý:** Các thư mục doc cũ (`doc/`, `plan/`, `API Contract/`, `TEST CASE MANUAL/`) vẫn được giữ lại. Sau khi xác nhận ổn, có thể xoá chúng.
