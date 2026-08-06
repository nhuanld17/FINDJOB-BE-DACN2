# 📋 PLAN: Fix chức năng "Đổi mật khẩu" (Change Password)

> **Ngày:** 2026-08-02
> **Phạm vi:** FINDJOB-BE (backend) — + 1 cải tiến nhỏ cho FINDJOB-ANDROID (tùy chọn)
> **Trạng thái:** Chờ duyệt

---

## 1. Tóm tắt vấn đề

Chức năng **Đổi mật khẩu** (`POST /api/v1/auth/change-password`) hiện có **2 lỗi nghiêm trọng**:

| # | Vấn đề | Mức độ | Ảnh hưởng |
|---|---|---|---|
| **BUG-1** | **403 Access Denied** khi đổi mật khẩu — kể cả token hợp lệ | 🔴 Cao | MỌI user đều không đổi được mật khẩu |
| **BUG-2** | **User đăng ký bằng Google không thể đổi mật khẩu** (deadlock "mật khẩu giả") | 🔴 Cao | User Google bị kẹt vĩnh viễn |

Ngoài ra, hướng fix BUG-2 nếu làm sai cách sẽ tạo ra **rủi ro bảo mật** (bỏ qua xác thực mật khẩu cũ vĩnh viễn).

---

## 2. Phân tích nguyên nhân gốc rễ

### 2.1 BUG-1: 403 Access Denied — `JwtAuthFilter` skip `change-password`

**Nguyên nhân:** `change-password` nằm trong pattern public `/api/v1/auth/**` (khai báo trong `SecurityConfig.PUBLIC_PATTERNS`). `JwtAuthFilter.shouldNotFilter()` trả `true` với mọi path khớp pattern này → **filter không bao giờ parse JWT** → `SecurityContext` luôn rỗng (anonymous) → `@PreAuthorize("isAuthenticated()")` trên controller luôn chặn.

```java
// SecurityConfig.java
public static final String[] PUBLIC_PATTERNS = {
        "/api/v1/auth/**",   // ← change-password NẰM TRONG đây
        ...
};

// JwtAuthFilter.shouldNotFilter()
for (String pattern : SecurityConfig.PUBLIC_PATTERNS) {
    if (pathMatcher.match(pattern, path)) {
        return true;   // ← SKIP — token không được đọc
    }
}
```

**Chuỗi xử lý:**
```
POST /api/v1/auth/change-password + Bearer <token hợp lệ>
  → JwtAuthFilter SKIP → SecurityContext rỗng
  → authorizeHttpRequests: /api/v1/auth/** permitAll → cho qua
  → @PreAuthorize("isAuthenticated()") → anonymous → 403
```

**Ảnh hưởng phụ trên Android:** interceptor `api.ts` chỉ refresh token khi nhận **401**. Với **403**, không refresh → user chỉ thấy "Access denied". Đặc biệt kịch bản **cold start** (access token lưu memory, mất khi đóng app; refresh token lưu SecureStore) → request đầu tiên sau khi mở app không có Authorization header → 403 → không refresh → bế tắc.

### 2.2 BUG-2: User Google không đổi được mật khẩu — Anti-pattern "mật khẩu giả"

**Nguyên nhân (đã xác nhận với leader):** User tạo từ Google được gán password = **hash của UUID ngẫu nhiên** (`CustomOidcUserService.loadUser()`), không ai biết password thật:

```java
user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));  // mật khẩu giả
user.setAuthProvider(AuthProvider.GOOGLE);
```

Khi đổi mật khẩu, `AuthServiceImplement.changePassword()` bắt buộc kiểm tra `oldPassword`:
```java
if (!passwordEncoder.matches(oldPassword, userDetails.getPassword())) {
    throw new AppException(ErrorCode.INVALID_CREDENTIALS);  // ← Google user LUÔN dính đây
}
```

→ User Google nhập oldPassword (không thể biết) → `matches()` luôn false → **deadlock**.
→ Việc sinh UUID random để "lấp chỗ trống" cột NOT NULL là **anti-pattern** (tốn CPU băm, sai lệch dữ liệu, gây bug logic).

---

## 3. Giải pháp

### 3.1 Hướng chuẩn (theo leader): Password là **Optional (NULL)**

Thiết kế đúng: **`password_hash` cho phép NULL**. User Google tạo ra với `password = NULL`, phân biệt trạng thái bằng *"có password hay không"* chứ không phải *"authProvider là gì"*.

```java
// Khi tạo user từ Google (CustomOidcUserService)
user.setPassword(null);   // 🟢 NULL — không băm UUID, không lừa dối DB

// Khi đổi mật khẩu (AuthServiceImplement.changePassword)
boolean hasPassword = (userDetails.getPassword() != null);
if (hasPassword) {
    // Đã có password → bắt buộc nhập đúng mật khẩu cũ
    if (!passwordEncoder.matches(oldPassword, userDetails.getPassword())) {
        throw new AppException(ErrorCode.INVALID_CREDENTIALS);
    }
}
// else: password NULL → user thuần Google → cho phép đặt mới, bỏ qua oldPassword
userRepository.updatePassword(userDetails.getId(), passwordEncoder.encode(newPassword));
```

> ✅ **Ưu điểm:** sau khi đặt password, lần sau `password != null` → tự động phải nhập mật khẩu cũ. Không cần đổi `authProvider`, không cần sửa `CustomUserDetails`. Sạch hơn hướng dùng `authProvider` làm điều kiện.

### 3.2 🔑 ĐIỂM BẮT BUỘC BỔ SUNG: Data migration dọn Google user cũ

> ⚠️ **Leader chưa đề cập nhưng BẮT BUỘC phải có:** DB hiện tại đã có Google user với `password = hash(UUID random)` (**KHÔNG NULL**). Nếu chỉ sửa code, những user này vẫn bị check oldPassword → **bug vẫn còn**.

```sql
-- V15__make_password_nullable_and_clean_oauth.sql

-- 1. Cho phép password NULL
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- 2. 🔑 DỌN DỮ LIỆU CŨ: Google user đang giữ hash UUID giả → set NULL
--    (Thiếu bước này = bug vẫn còn với user đăng ký Google trước đây!)
UPDATE users SET password = NULL WHERE auth_provider = 'GOOGLE';
```

### 3.3 Fix BUG-1 (kèm trong cùng PR)

2 thay đổi nhỏ khôi phục chức năng đổi mật khẩu cho mọi user (đã thống nhất ở các phiên trước):

**`JwtAuthFilter.shouldNotFilter()`** — ngoại lệ cho `change-password`:
```java
if (pathMatcher.match(pattern, path)) {
    // NGOẠI LỆ: change-password yêu cầu đăng nhập → phải parse JWT
    if ("/api/v1/auth/change-password".equals(path)) {
        return false;
    }
    return true;
}
```

**`SecurityConfig`** — matcher cụ thể đứng TRƯỚC permitAll (first-match-wins):
```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/auth/change-password").authenticated()
        .requestMatchers(PUBLIC_PATTERNS).permitAll()
        .anyRequest().authenticated()
)
```

---

## 4. Danh sách thay đổi chi tiết

| # | File | Thay đổi | Giải quyết |
|---|---|---|---|
| 1 | `src/main/resources/db/migration/V15__make_password_nullable_and_clean_oauth.sql` | **MỚI**: `DROP NOT NULL` + `UPDATE ... WHERE auth_provider='GOOGLE'` | BUG-2 (bắt buộc) |
| 2 | `features/user/entity/User.java` | `@Column(nullable = false)` → `nullable = true` cho `password` | BUG-2 |
| 3 | `infrastructure/security/oauth2/CustomOidcUserService.java` | `setPassword(encode(UUID...))` → `setPassword(null)` | BUG-2 |
| 4 | `features/auth/service/impl/AuthServiceImplement.java` | `changePassword()`: check `password != null` thay vì check thẳng `matches()` | BUG-2 |
| 5 | `infrastructure/security/jwt/JwtAuthFilter.java` | Ngoại lệ `change-password` trong `shouldNotFilter()` | BUG-1 |
| 6 | `infrastructure/security/SecurityConfig.java` | Thêm matcher `change-password → authenticated` trước permitAll | BUG-1 |
| 7 | `features/auth/service/impl/AuthServiceImplement.java` (dòng ~1050) | `googleLogin()` (deprecated): `setPassword(null)` cho đồng bộ | BUG-2 (P1) |

---

## 5. Kiểm chứng an toàn

### 5.1 Password NULL có làm vỡ login không? — ✅ KHÔNG

Đã kiểm tra `UserDetailsServiceImpl` + `DaoAuthenticationProvider`:
- `BCryptPasswordEncoder.matches(raw, null)` → trả `false` (KHÔNG NPE)
- Google user login bằng Google: OIDC flow **bypass cột password** → vẫn OK
- Google user cố login bằng email/password: `matches(x, null)` = false → từ chối → đúng hành vi

### 5.2 Không phá vỡ user LOCAL

- User LOCAL có password thật → `password != null` → vẫn check oldPassword như cũ
- User LOCAL link Google: `authProvider` giữ LOCAL, `socialId` giữ nguyên → cả 2 cách login đều hoạt động

### 5.3 Rủi ro data migration

`UPDATE users SET password = NULL WHERE auth_provider = 'GOOGLE'` khiến Google user cũ **không login password được** (họ chưa từng có password thật nên không mất gì). Nên **chạy trên bản backup trước khi deploy**.

---

## 6. Kế hoạch triển khai

| Giai đoạn | Nội dung | Ưu tiên |
|---|---|---|
| **P0** | Migration V15 + `User.java` + `CustomOidcUserService` + `changePassword()` | Bắt buộc |
| **P0** | Fix 403: `JwtAuthFilter` + `SecurityConfig` (kèm cùng PR) | Bắt buộc |
| **P1** | Đồng bộ `googleLogin()` deprecated: `setPassword(null)` | Nên làm |
| **P2** | (Tùy chọn — cần product quyết) Verify OTP email khi user Google đặt password lần đầu | Để sau |

### Checklist test sau khi implement

- [ ] User LOCAL: sai mật khẩu cũ → lỗi; đúng → đổi thành công, thiết bị khác bị đăng xuất
- [ ] User Google CŨ (đã có hash UUID trong DB): sau migration → đặt mật khẩu lần đầu thành công
- [ ] User Google MỚI: đặt mật khẩu lần đầu thành công (không cần oldPassword)
- [ ] User Google sau khi đặt password: đổi lần 2 **phải nhập đúng** mật khẩu cũ
- [ ] User Google vẫn login được bằng Google sau khi đã đặt password
- [ ] Login bằng email/password hoạt động cho user đã đặt password
- [ ] Cold start app (Android): token hết hạn → tự refresh → đổi mật khẩu thành công (không còn 403)

---

## 7. Ước lượng

- **File:** ~7 file (6 code + 1 migration)
- **Độ phức tạp:** Thấp — tổng ~60-80 dòng thay đổi
- **Không cần:** thay đổi schema ngoài migration, thay đổi API contract, thay đổi Android (chỉ cần nếu muốn UI khác biệt)

---

## 8. Phụ lục — Bối cảnh review với leader

Hướng dẫn của leader (password NULL, check `hasPassword`) được ghi nhận là **đúng chuẩn enterprise**. Plan này bổ sung thêm 1 điểm quan trọng mà leader chưa đề cập: **data migration dọn Google user cũ** — thiếu bước này thì BUG-2 vẫn tồn tại với user đã đăng ký Google trước đây.
