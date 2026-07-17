<div align="center">

# 🚀 Spring Boot Boilerplate

**A production-ready Spring Boot 3.4 boilerplate with JWT auth, OTP verification, OAuth2/OIDC (Google), Redis session management, and more.**

[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4.2-6DB33F?logo=spring-boot)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=java)](https://www.oracle.com/java/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 📋 Table of Contents

- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Architecture](#-architecture)
- [Getting Started](#-getting-started)
- [Configuration](#-configuration)
- [API Endpoints](#-api-endpoints)
- [Authentication Flow](#-authentication-flow)
- [OAuth2 / OIDC (Google Login)](#-oauth2--oidc-google-login)
- [Security](#-security)
- [Database Migrations](#-database-migrations)
- [Testing](#-testing)
- [Deployment](#-deployment)
- [Project Structure](#-project-structure)
- [FAQ](#-faq)

---

## ✨ Features

### 🔐 Authentication
- **Register** with email verification via OTP
- **Login** with username/email + password
- **OTP** (One-Time Password) sent via email with cooldown & rate limiting
- **JWT** access token (short-lived) + refresh token (long-lived, HttpOnly cookie)
- **Refresh token** rotation with reuse detection
- **Logout** with token blacklisting & session invalidation
- **Google OAuth2 / OIDC** login with one-time ticket exchange

### 🛡️ Security
- BCrypt password encoding
- JWT with configurable expiration
- HttpOnly + SameSite=Strict cookies for refresh tokens
- CORS configured for frontend development
- Session management via Redis
- Token reuse detection (family rotation)
- Device-aware session tracking

### ⚙️ Infrastructure
- PostgreSQL 16 with Flyway migrations
- Redis 7 for session storage & rate limiting
- Docker Compose for local development
- Async email sending with dedicated thread pool
- Swagger UI at `/swagger-ui/index.html`
- Spring Boot Actuator health checks
- SonarQube & JaCoCo for code quality

---

## 🛠️ Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| **Language** | Java | 21 |
| **Framework** | Spring Boot | 3.4.2 |
| **Security** | Spring Security + JWT (jjwt) | 0.12.6 |
| **OAuth2** | Spring Security OAuth2 Client | — |
| **Database** | PostgreSQL | 16 |
| **Cache/Session** | Redis | 7 |
| **ORM** | Spring Data JPA / Hibernate | — |
| **Migration** | Flyway | — |
| **Mail** | Spring Mail + Thymeleaf | — |
| **API Docs** | SpringDoc OpenAPI (Swagger) | 2.8.4 |
| **Build** | Maven | — |
| **Code Gen** | Lombok + MapStruct | 1.6.3 |
| **Testing** | JUnit 5 + Testcontainers | — |
| **Monitoring** | Spring Boot Actuator | — |

---

## 🏗️ Architecture

The project follows a **layered architecture** with **package-by-feature** organization:

```
┌─────────────────────────────────────────────┐
│              Presentation Layer              │
│        (Controllers, DTOs)                  │
├─────────────────────────────────────────────┤
│                Service Layer                 │
│        (Business Logic, Validation)          │
├─────────────────────────────────────────────┤
│               Persistence Layer              │
│        (Entities, Repositories, JPA)         │
├─────────────────────────────────────────────┤
│            Infrastructure Layer              │
│  (Security, Redis, Mail, OAuth2/OIDC)        │
└─────────────────────────────────────────────┘
```

### Package Structure

```
com.example.boilerplate/
├── common/               ← Shared cross-cutting code
│   ├── base/             → BaseEntity (createdAt, updatedAt, deleted)
│   ├── config/           → CorsConfig, RedisConfig, AsyncConfig
│   ├── constant/         → ErrorCode, SuccessCode, JwtConstant, RoleEnum, AuthProvider
│   ├── exception/        → AppException, GlobalExceptionHandler, AccountBannedException
│   ├── response/         → APIResponse<T>, ErrorResponse (standard API format)
│   └── util/             → RequestUtils (IP, User-Agent extraction)
├── features/             ← Business features grouped by domain
│   ├── auth/             → Register, Login, OTP, Refresh, Logout, OIDC exchange
│   │   ├── controller/   → AuthController
│   │   ├── dto/          → Request/Response DTOs
│   │   └── service/      → AuthService + impl
│   └── user/             → User entity, Role entity, repositories
│       ├── entity/       → User.java, Role.java
│       ├── repository/   → UserRepository, RoleRepository
│       └── service/      → UserService
└── infrastructure/       ← Technical infrastructure
    ├── mail/             → EmailService (async OTP/welcome emails)
    ├── redis/            → RedisService (session, blacklist, OTP state)
    └── security/         ← Security layer
        ├── jwt/          → JwtAuthFilter, JwtUtil, JwtAccessDeniedHandler, JwtAuthEntryPoint
        ├── oauth2/       → CustomOidcUserService, CustomOidcUser, handlers
        ├── SecurityConfig.java
        ├── CustomUserDetails.java
        └── UserDetailsServiceImpl.java
```

---

## 🚀 Getting Started

### Prerequisites

- **Java 21+**
- **Docker & Docker Compose** (for PostgreSQL + Redis)
- **Maven** (or use the included `mvnw` wrapper)

### Step 1: Clone & Setup Environment

```bash
git clone <your-repo-url>
cd spring-boot-boilerplate
cp .env.example .env
```

Edit `.env` with your configuration:

```properties
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=boilerplate
DB_USERNAME=postgres
DB_PASSWORD=change_me
DB_SCHEMA=public

# JWT (generate with: openssl rand -base64 64)
JWT_SECRET=your_base64_encoded_secret_here
ACCESS_TOKEN_LIFETIME=900000       # 15 minutes
REFRESH_TOKEN_LIFETIME=604800000   # 7 days

# SMTP (Gmail example)
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your_email@gmail.com
MAIL_PASSWORD=your_app_password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=1

# Optional: Google OAuth2
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
```

### Step 2: Start Infrastructure

```bash
docker compose up -d
```

This starts PostgreSQL (port 5432) and Redis (port 6379).

### Step 3: Run the Application

```bash
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080**.

### Step 4: Verify

```bash
# Health check
curl http://localhost:8080/actuator/health

# Swagger UI
# Open http://localhost:8080/swagger-ui/index.html in your browser
```

---

## ⚙️ Configuration

### Environment Variables (.env)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_HOST` | ✅ | `localhost` | PostgreSQL host |
| `DB_PORT` | ✅ | `5432` | PostgreSQL port |
| `DB_NAME` | ✅ | `boilerplate` | Database name |
| `DB_USERNAME` | ✅ | `postgres` | Database user |
| `DB_PASSWORD` | ✅ | — | Database password |
| `DB_SCHEMA` | ✅ | `public` | Database schema |
| `JWT_SECRET` | ✅ | — | Base64-encoded JWT signing secret |
| `ACCESS_TOKEN_LIFETIME` | ✅ | `900000` | Access token TTL (ms) |
| `REFRESH_TOKEN_LIFETIME` | ✅ | `604800000` | Refresh token TTL (ms) |
| `MAIL_HOST` | ✅ | `smtp.gmail.com` | SMTP server host |
| `MAIL_PORT` | ✅ | `587` | SMTP server port |
| `MAIL_USERNAME` | ✅ | — | SMTP username |
| `MAIL_PASSWORD` | ✅ | — | SMTP password |
| `REDIS_HOST` | ✅ | `localhost` | Redis host |
| `REDIS_PORT` | ✅ | `6379` | Redis port |
| `REDIS_PASSWORD` | ❌ | — | Redis password |
| `REDIS_DATABASE` | ✅ | `1` | Redis database index |
| `GOOGLE_CLIENT_ID` | ❌ | — | Google OAuth2 Client ID |
| `GOOGLE_CLIENT_SECRET` | ❌ | — | Google OAuth2 Client Secret |
| `APP_OAUTH2_REDIRECT_URL` | ❌ | `http://localhost:5173/oauth-callback` | OAuth2 frontend redirect |

### Application Properties (application.yml)

Key configurations in `src/main/resources/application.yml`:

- **Database**: PostgreSQL with Flyway auto-migration
- **JPA**: `ddl-auto: none` (Flyway manages schema), `open-in-view: false`
- **Redis**: Lettuce connection pool (max 10 active)
- **Mail**: SMTP with STARTTLS
- **Security**: Stateless sessions, public endpoints, OAuth2 login
- **CORS**: Frontend at `http://localhost:5173` (Vite default)

---

## 📡 API Endpoints

Base URL: `http://localhost:8080/api/v1/auth`

### Authentication

| Method | Endpoint | Description | Auth Required |
|--------|----------|-------------|:---:|
| `POST` | `/register` | Register new user (sends OTP email) | ❌ |
| `POST` | `/verify-otp` | Verify OTP code to activate account | ❌ |
| `POST` | `/resend-otp` | Resend OTP email | ❌ |
| `POST` | `/login` | Login with username/password | ❌ |
| `POST` | `/refresh-token` | Refresh access token (uses cookie) | ❌ |
| `POST` | `/logout` | Logout (blacklists token, clears session) | ❌ |
| `POST` | `/exchange-ticket` | Exchange OAuth2 one-time ticket for JWT | ❌ |

### Example Requests

#### Register

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "johndoe",
    "email": "john@example.com",
    "password": "StrongP@ss123",
    "confirmPassword": "StrongP@ss123",
    "fullName": "John Doe",
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "deviceName": "Chrome on Windows"
  }'
```

#### Login

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "johndoe",
    "password": "StrongP@ss123",
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "deviceName": "Chrome on Windows"
  }'
```

#### Verify OTP

```bash
curl -X POST http://localhost:8080/api/v1/auth/verify-otp \
  -H "Content-Type: application/json" \
  -d '{"otp": "123456"}' \
  --cookie "pendingToken=xxx"
```

### Response Format

**Success (HTTP 200):**
```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": 1,
    "username": "johndoe",
    "roles": ["ROLE_USER"]
  }
}
```

**Error (HTTP 4xx/5xx):**
```json
{
  "status": 401,
  "code": 3009,
  "message": "Unauthorized",
  "timestamp": "2026-07-16T14:38:20.373Z"
}
```

---

## 🔄 Authentication Flow

### Standard Registration Flow

```
User                    Frontend                  Backend                 Redis          Email
 │                        │                         │                      │              │
 │── Register form ──────►│                         │                      │              │
 │                        │── POST /register ──────►│                      │              │
 │                        │                         │── Save pending ─────►│              │
 │                        │                         │── Send OTP ──────────────────────────►│
 │                        │◄─ Set-Cookie:           │                      │              │
 │                        │   pendingToken          │                      │              │
 │                        │                         │                      │              │
 │── Enter OTP ──────────►│                         │                      │              │
 │                        │── POST /verify-otp ────►│                      │              │
 │                        │                         │── Verify OTP ───────►│              │
 │                        │                         │── Activate user ─────│              │
 │                        │                         │── Generate JWT ─────►│              │
 │                        │◄─ Set-Cookie:           │                      │              │
 │                        │   refreshToken          │                      │              │
 │◄─ Access token ────────│                         │                      │              │
```

### Login Flow (Inactive → OTP Verification)

```
User                    Frontend                  Backend
 │                        │                         │
 │── Login form ─────────►│                         │
 │                        │── POST /login ─────────►│
 │                        │                         │── Check active status
 │                        │                         │── User inactive?
 │                        │                         │── Send OTP / reuse existing
 │                        │◄─ LoginInactiveResponse │
 │                        │   + pendingToken cookie │
 │                        │   + OTP info            │
 │                        │                         │
 │── Verify OTP ─────────►│                         │
 │                        │── POST /verify-otp ────►│
 │                        │                         │── OTP correct?
 │                        │                         │── Activate + generate JWT
 │                        │◄─ AuthResponse          │
 │◄─ Access token         │   + refreshToken cookie │
```

### Refresh Token Flow

```
User                    Frontend                  Backend                 Redis
 │                        │                         │                      │
 │── Call API ───────────►│                         │                      │
 │                        │── POST /refresh-token ──►│                      │
 │                        │   (cookie: refreshToken) │                      │
 │                        │                         │── Decode RT          │
 │                        │                         │── Check blacklist ──►│
 │                        │                         │── Check session ────►│
 │                        │                         │── Rotate token ─────►│
 │                        │◄─ New AuthResponse      │                      │
 │◄─ New access token     │   + new refresh cookie  │                      │
```

### Logout Flow

```
User                    Frontend                  Backend                 Redis
 │                        │                         │                      │
 │── Logout ─────────────►│                         │                      │
 │                        │── POST /logout ────────►│                      │
 │                        │   (cookie: refreshToken) │                      │
 │                        │   (header: Bearer AT)    │                      │
 │                        │                         │── Blacklist AT ─────►│
 │                        │                         │── Blacklist RT ─────►│
 │                        │                         │── Remove session ───►│
 │                        │◄─ 200 OK                │                      │
 │◄─ Logged out           │   + clear cookie        │                      │
```

---

## 🔑 OAuth2 / OIDC (Google Login)

The project supports **login with Google** using OpenID Connect (OIDC).

### How It Works

```
Browser                   Backend                   Google                  Redis
 │                          │                         │                      │
 │── /oauth2/authorization/google                     │                      │
 │   (window.location.href) │                         │                      │
 │                          │── 302 Redirect ────────►│                      │
 │◄─ Redirect ──────────────│                         │                      │
 │                          │                         │                      │
 │── Login + consent ────────────────────────────────►│                      │
 │                          │◄─ Auth code ────────────│                      │
 │                          │                         │                      │
 │                          │── POST /token ─────────►│                      │
 │                          │◄─ ID Token ─────────────│                      │
 │                          │                         │                      │
 │                          │── loadUser()            │                      │
 │                          │   (validate, Create/    │                      │
 │                          │    Link existing user)  │                      │
 │                          │                         │                      │
 │                          │── Save ticket ────────────────────────────────►│
 │◄─ 302 ?ticket=xxx ───────│                         │                      │
 │                          │                         │                      │
 │── POST /exchange-ticket──►                         │                      │
 │                          │── GETDEL(ticket) ────────────────────────────►│
 │                          │── Create session ─────────────────────────────►│
 │◄─ AuthResponse + cookie ──│                        │                      │
 ```

### Setup Google OAuth2

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project → **APIs & Services** → **Credentials**
3. Create **OAuth 2.0 Client ID** (Web application)
4. Add redirect URI: `http://localhost:8080/login/oauth2/code/google`
5. Copy Client ID & Secret to `.env`:
   ```properties
   GOOGLE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=GOCSPX-xxxxxxxx
   ```

### Test with curl + Browser

```http
### Step 1: Open this URL in your browser
### http://localhost:8080/oauth2/authorization/google

### Step 2: After Google login, copy ticket from URL
### http://localhost:5173/oauth-callback?ticket=YOUR_TICKET

### Step 3: Exchange ticket for access token
POST http://localhost:8080/api/v1/auth/exchange-ticket
Content-Type: application/json

{
  "ticket": "YOUR_TICKET"
}
```

### Security Design for OAuth2

| Measure | Implementation |
|---------|---------------|
| One-time ticket | Atomic `GETDEL` (Lua script) prevents replay attacks |
| Ticket TTL | 60 seconds — short window for misuse |
| Email verification | `email_verified` claim must be `true` (prevents account takeover) |
| Random password | OIDC users get an unrecoverable random hash — password login impossible |
| AuthProvider preserved | Existing LOCAL users keep `authProvider=LOCAL` after linking Google |
| Auto-activation | Inactive LOCAL users are auto-activated on first Google login |
| Log safety | Only 8 characters of ticket logged — no credential leak |

---

## 🛡️ Security

### Token Management

| Aspect | Implementation |
|--------|---------------|
| **Access Token** | JWT, short-lived (default 15 min), contains userId + roles + sessionId |
| **Refresh Token** | JWT, long-lived (default 7 days), stored in HttpOnly cookie |
| **Token Rotation** | Each refresh generates a new refresh token + invalidates the old one |
| **Reuse Detection** | If a used refresh token is presented again → all tokens for that family are revoked |
| **Blacklisting** | Both AT and RT are blacklisted in Redis on logout |
| **Session Management** | Each login creates a Redis session; sessions are tracked per user |

### Security Headers

| Header | Value |
|--------|-------|
| `Set-Cookie` (refreshToken) | `HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=604800` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `X-XSS-Protection` | `0` |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` |

### CORS

Configured for `http://localhost:5173` (Vite dev server).  
Allow credentials enabled (required for cookie-based flow).

---

## 📦 Database Migrations

The project uses **Flyway** for database version control:

```
src/main/resources/db/migration/
├── V1__init_schema.sql           ← Core tables: roles, users, user_role
├── V2__normalize_role_enum_values.sql
├── V3__seed_e2e_test_accounts.sql
├── V4__add_unique_constraint_username.sql
├── V5__add_oidc_fields.sql       ← OAuth2 fields (auth_provider, social_id, avatar_url)
```

### Schema Overview

```sql
-- Roles
roles (id BIGSERIAL PK, name VARCHAR UNIQUE)

-- Users
users (
  id BIGSERIAL PK,
  username VARCHAR NOT NULL UNIQUE,
  email VARCHAR NOT NULL UNIQUE,
  password VARCHAR NOT NULL,
  full_name VARCHAR,
  is_active BOOLEAN DEFAULT false,
  is_deleted BOOLEAN DEFAULT false,
  auth_provider VARCHAR(20) DEFAULT 'LOCAL',
  social_id VARCHAR(200),
  avatar_url VARCHAR(500),
  created_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ
)

-- User-Role (many-to-many)
user_role (user_id FK, role_id FK, PRIMARY KEY)
```

---

## 🧪 Testing

```bash
# Run all tests
./mvnw test

# Run with coverage
./mvnw verify

# Run specific test class
./mvnw test -Dtest=AuthServiceTest
```

The project includes:
- **JUnit 5** — test framework
- **Testcontainers** — PostgreSQL + Redis containers for integration tests
- **Spring Security Test** — `@WithMockUser`, `SecurityMockMvcRequestPostProcessors`
- **JaCoCo** — code coverage reports at `target/site/jacoco/`

---

## 🚢 Deployment

### Build

```bash
./mvnw clean package -DskipTests
```

Produces an executable JAR at `target/boilerplate-0.0.1-SNAPSHOT.jar`.

### Run

```bash
java -jar target/boilerplate-0.0.1-SNAPSHOT.jar
```

### Production Considerations

1. **Set strong secrets** — `JWT_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD`
2. **Update CORS origins** — modify `CorsConfig.java` with your production domain
3. **Configure proper SMTP** — use a transactional email service (SendGrid, Mailgun, etc.)
4. **Set up SSL/TLS** — use a reverse proxy (Nginx) or let Spring Boot handle it
5. **Disable `show-sql`** — set `spring.jpa.show-sql: false` in production
6. **Health checks** — use `/actuator/health` with Kubernetes / Docker health probes

---

## 📝 Project Structure (Full)

```
├── .env.example                          ← Environment template
├── docker-compose.yml                    ← PostgreSQL + Redis
├── pom.xml                               ← Maven build config
├── mvnw / mvnw.cmd                       ← Maven wrapper
│
├── src/main/java/com/example/boilerplate/
│   ├── BoilerplateApplication.java       ← Spring Boot entry point
│   │
│   ├── common/                           ← Cross-cutting concerns
│   │   ├── base/BaseEntity.java          ← JPA base (createdAt, updatedAt, deleted)
│   │   ├── config/                       ← @Configuration classes
│   │   ├── constant/                     ← Enums & constants
│   │   ├── exception/                    ← Custom exceptions + handler
│   │   ├── response/                     ← Standardized API response models
│   │   └── util/RequestUtils.java        ← HTTP request helpers
│   │
│   ├── features/                         ← Business features
│   │   ├── auth/                         ← Authentication
│   │   │   ├── controller/AuthController.java
│   │   │   ├── dto/request/              ← Register, Login, VerifyOtp, ExchangeTicket
│   │   │   └── dto/response/             ← AuthResponse, LoginInactiveResponse, etc.
│   │   │   └── service/                  ← AuthService + AuthServiceImplement
│   │   └── user/                         ← User management
│   │       ├── entity/User.java, Role.java
│   │       ├── repository/UserRepository, RoleRepository
│   │       └── service/UserService.java
│   │
│   └── infrastructure/                   ← Technical infrastructure
│       ├── mail/EmailService.java        ← Async email via SMTP
│       ├── redis/RedisService.java       ← Session & cache management
│       └── security/                     ← Security layer
│           ├── jwt/                      ← JWT filter, util, handlers
│           ├── oauth2/                   ← OIDC user service, handlers
│           ├── SecurityConfig.java       ← Spring Security configuration
│           ├── CustomUserDetails.java    ← Custom UserDetails implementation
│           └── UserDetailsServiceImpl.java ← UserDetailsService impl
│
└── src/main/resources/
    ├── application.yml                   ← Application config
    ├── db/migration/                     ← Flyway SQL migrations
    └── templates/email/                  ← Thymeleaf email templates
```

---

## ❓ FAQ

### Why `open-in-view: false`?

Open Session in View (OSIV) is disabled to prevent `LazyInitializationException` from being silently swallowed and to enforce proper transaction boundaries. All queries should be done within `@Transactional` or use `JOIN FETCH` for lazy associations.

### Why `ddl-auto: none`?

Schema management is handled by **Flyway**, which provides version-controlled, repeatable migrations. Hibernate's `ddl-auto` is set to `none` to avoid conflicts.

### Why separate `auth` and `user` features?

The `auth` module handles authentication flows (register, login, OTP, tokens), while `user` manages the user entity and profile. This separation keeps concerns clean — `auth` can be swapped or extended without touching user management.

### How does OTP rate limiting work?

OTP sends are rate-limited with:
- **Cooldown period** — cannot resend OTP within a short window
- **Max attempts** — limit on wrong OTP entries before blocking
- **Max sends** — limit on OTP resend requests per session
- All state is tracked in Redis with appropriate TTLs

### How does refresh token rotation work?

Each time you call `/refresh-token`:
1. The old refresh token is validated and blacklisted
2. A new refresh token (new jti) is generated
3. The session in Redis is updated with the new jti
4. If a used token is presented again → reuse detection kicks in, invalidating all tokens for that device family

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">
Made with ❤️ by <a href="https://github.com/nhuanld17">Lê Đình Nhuận</a>
</div>
