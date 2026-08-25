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
| 3 | [Mobile OIDC Flow](02-oidc/03-mobile-oidc-flow.md) | Flow OIDC phía mobile (deep link, exchange ticket, so sánh Web vs Mobile) |

## 📋 API Contracts (`03-api-contracts/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Login & Refresh Token](03-api-contracts/01-login-refresh-token.md) | API contract cho login + refresh token |
| 2 | [Logout](03-api-contracts/02-logout.md) | API contract cho logout |
| 3 | [Register & OTP](03-api-contracts/03-register-otp.md) | API contract cho register + OTP |
| 4 | [Change Password](03-api-contracts/04-change-password.md) | API contract cho đổi/đặt mật khẩu (dual-mode: user LOCAL vs Google) |
| 5 | [Exchange Ticket](03-api-contracts/05-exchange-ticket.md) | API contract cho OIDC mobile login (one-time ticket → JWT) |
| 6 | [Jobs](03-api-contracts/06-jobs.md) | API contract cho jobs + categories (create/update/delete/search/keyset) |
| 7 | [Applications](03-api-contracts/07-applications.md) | API contract cho ứng tuyển (USER apply/cancel, COMPANY quản lý ứng viên) |
| 8 | [Companies & Reviews](03-api-contracts/08-companies-reviews.md) | API contract cho công ty + đánh giá công ty |
| 9 | [Employees, Saved Jobs & Follows](03-api-contracts/09-employees-saved-follows.md) | API contract cho hồ sơ ứng viên + lưu job + follow công ty |
| 10 | [ATS](03-api-contracts/10-ats.md) | API contract cho AI chấm CV (Groq + cache Redis) |

## 🧪 Test Cases (`04-test-cases/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Authentication Test Cases](04-test-cases/01-authentication.md) | Test cases cho Authentication module (OTP Flow) |
| 2 | [Access Token & Refresh Token Test](04-test-cases/02-access-refresh-token.md) | Test cases cho AT & RT |
| 3 | [Manual Test Template](04-test-cases/03-manual-test-template.md) | Template cho manual testing |
| 4 | [Business APIs](04-test-cases/04-business-apis.md) | Test cases tay cho Jobs/Applications/Companies/Reviews/Employees/Saved/Follows/ATS |

## 📝 Development Plans (`05-plans/`)

| # | File | Mô tả |
|---|------|-------|
| 1 | [Integrate OIDC](05-plans/01-integrate-oidc.md) | Plan tích hợp Google OIDC (3 phases) |
| 2 | [Fix Change Password](05-plans/02-fix-change-password.md) | Plan fix change-password cho user Google (password NULL) |

## 🗄️ Database (`database-design/`)

| File | Mô tả |
|------|-------|
| [Database Design](database-design/db.md) | Thiết kế CSDL + đối chiếu 15 migration thực tế (V1–V15) |

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
├── database-design/                   ← 🗄️ Database design
└── notes/                             ← 📓 Notes
```

> ✅ Các thư mục doc cũ (`doc/`, `plan/`, `API Contract/`, `TEST CASE MANUAL/`) đã được dọn dẹp — toàn bộ tài liệu tập trung tại `docs/`.
