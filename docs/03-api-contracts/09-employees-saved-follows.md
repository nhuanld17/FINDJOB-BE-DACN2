# FE API Contract - Employees + Saved Jobs + Follows

> **Cập nhật 2026-08-06:** Contract viết theo code hiện tại (`EmployeeController`, `SavedJobController`, `FollowController` + services). Gồm 11 endpoint employee, 4 endpoint saved-jobs, 4 endpoint follows.

## 1. Overview

- **Base paths:** `/api/v1/employees`, `/api/v1/saved-jobs`, `/api/v1/follows`
- **Auth:** endpoint USER cần `Authorization: Bearer` + role `USER`; riêng `GET /employees/me`, `PUT /employees/me`, `POST /employees/me/avatar` chỉ cần đăng nhập (`isAuthenticated()` — dùng chung cho USER/COMPANY)

### 1.1 `EmployeeResponse` (profile)

```json
{
  "id": 3,
  "userId": 10,
  "fullName": "Nguyễn Văn A",
  "avatarUrl": "https://.../avatar.png",
  "phone": "09xxxxxxxx",
  "dateOfBirth": "1998-05-20",
  "gender": "Nam",
  "city": "HA_NOI",
  "address": "...",
  "cvUrl": null,
  "githubUrl": "...",
  "linkedinUrl": "...",
  "portfolioUrl": "...",
  "isPublic": true,
  "isOpenToWork": true,
  "title": "Software Engineer",
  "bio": "...",
  "skills": ["Java", "Spring Boot"],
  "experiences": [ "Map<string, object>..." ],
  "education": [ "Map<string, object>..." ],
  "certificates": [
    { "id": 1, "name": "AWS SAA", "issuer": "Amazon", "issueDate": "2025-01-01", "expiryDate": "2028-01-01", "credentialUrl": "..." }
  ],
  "createdAt": "...",
  "updatedAt": "..."
}
```

> `experiences`/`education` là `List<Map<String,Object>>` — format linh hoạt, FE render trực tiếp. `cvUrl` đang không được dùng trong flow apply (xem contract Applications).

---

## 2. Employee endpoints

## 2.1 `GET /api/v1/employees/me` — Profile của tôi

**Auth:** `isAuthenticated()`.

- User chưa có profile employee → `2014 EMPLOYEE_NOT_FOUND` (VD: user đăng ký là COMPANY)

## 2.2 `PUT /api/v1/employees/me` — Cập nhật thông tin cơ bản

**Auth:** `isAuthenticated()`.

Request (`UpdateEmployeeRequest`) — **tất cả field optional** (partial):

```json
{
  "phone": "09xxxxxxxx",
  "dateOfBirth": "1998-05-20",
  "gender": "Nam",
  "city": "HA_NOI",
  "address": "...",
  "cvUrl": null,
  "githubUrl": "...",
  "linkedinUrl": "...",
  "portfolioUrl": "...",
  "isPublic": true,
  "isOpenToWork": true,
  "title": "Software Engineer",
  "bio": "..."
}
```

- **Không** bao gồm skills/experiences/education/certificates — chúng có endpoint riêng (§2.4–2.7)
- `city` là String free-form (không bắt buộc enum — khác với Job)
- `dateOfBirth` format `yyyy-MM-dd`

## 2.3 `POST /api/v1/employees/me/avatar` — Upload avatar

**Auth:** `isAuthenticated()`.

**Content-Type:** `multipart/form-data` — field bắt buộc `file` (ảnh).

- Upload Cloudinary folder `employees/{userId}/avatar` (image types)
- Avatar cũ tự xoá khi upload mới
- Success: `200` + `APIResponse<EmployeeResponse>` (có `avatarUrl` mới)

## 2.4 `PUT /api/v1/employees/me/skills` — Cập nhật kỹ năng

**Auth:** `hasRole('USER')`.

Request — **JSON array, ghi đè toàn bộ**:

```json
["Java", "Spring Boot", "PostgreSQL"]
```

## 2.5 `PUT /api/v1/employees/me/experiences` — Cập nhật kinh nghiệm

**Auth:** `hasRole('USER')`. Ghi đè toàn bộ — JSON array:

```json
[
  {
    "company": "FPT Software",
    "position": "Java Developer",
    "description": "...",
    "startDate": "2022-01-01",
    "endDate": "2024-06-30",
    "isCurrent": false
  }
]
```

- `isCurrent: true` → `endDate` có thể null
- Trường hợp `endDate < startDate` → lỗi `2040 INVALID_DATE_RANGE`

## 2.6 `PUT /api/v1/employees/me/education` — Cập nhật học vấn

**Auth:** `hasRole('USER')`. Ghi đè toàn bộ:

```json
[
  {
    "school": "ĐH Bách Khoa HN",
    "degree": "Cử nhân",
    "major": "CNTT",
    "description": "...",
    "startDate": "2016-09-01",
    "endDate": "2020-06-01",
    "isCurrent": false
  }
]
```

## 2.7 Certificates — CRUD riêng

**Auth:** tất cả `hasRole('USER')`.

| # | Method + Path | Body | Ghi chú |
|---|---|---|---|
| 1 | `GET /me/certificates` | — | Danh sách `List<CertificateResponse>` |
| 2 | `POST /me/certificates` | `CertificateRequest` | Tạo mới |
| 3 | `PUT /me/certificates/{certId}` | `CertificateRequest` | Sửa (chỉ của mình — sai → `2033 CERTIFICATE_NOT_FOUND`) |
| 4 | `DELETE /me/certificates/{certId}` | — | Xoá |

`CertificateRequest`:

```json
{
  "name": "AWS SAA",
  "issuer": "Amazon",
  "issueDate": "2025-01-01",
  "expiryDate": "2028-01-01",
  "credentialUrl": "https://..."
}
```

| Field | Bắt buộc | Rule |
|---|---|---|
| `name` | ✅ | `@NotBlank`, max 255 |
| `issuer` | ❌ | max 255 |
| `issueDate`/`expiryDate` | ❌ | `yyyy-MM-dd` |
| `credentialUrl` | ❌ | max 500 |

## 2.8 `GET /api/v1/employees/{id}` — Public profile

**Auth:** không cần (public — cả COMPANY xem được).

- Employee `isPublic = false` **hoặc** user bị ban → **404** (ẩn hoàn toàn)
- Trả `EmployeeResponse` (nhưng theo logic service, các field nhạy cảm bị ẩn qua `isPublic` check)

## 2.9 `GET /api/v1/employees/search` — Tìm kiếm ứng viên (COMPANY)

**Auth:** `hasRole('COMPANY')`.

Query params:

| Param | Optional | Mô tả |
|---|---|---|
| `search` | ✅ | Từ khoá: tên / chức danh / kỹ năng (containsIgnoreCase) |
| `skills` | ✅ | Danh sách kỹ năng, **cách nhau dấu phẩy**, match **tất cả** |
| `city` | ✅ | Tên thành phố (String) |
| `isOpenToWork` | ✅ | `true`/`false` — lọc người sẵn sàng làm việc |
| `page` | ✅ | default 0 |
| `size` | ✅ | default 20 |

**Quan trọng:**
- **CHỈ trả hồ sơ công khai** (`isPublic = true`) — profile private không xuất hiện
- Loại user bị ban
- Route `/search` (literal) được Spring ưu tiên hơn `/{id}` — không conflict
- Item = `CandidateSummaryResponse`:

```json
{
  "id": 3,
  "fullName": "Nguyễn Văn A",
  "avatarUrl": "...",
  "title": "Software Engineer",
  "city": "HA_NOI",
  "bio": "...",
  "isOpenToWork": true,
  "skills": ["Java", "Spring"],
  "updatedAt": "..."
}
```

> Không gồm email/phone/address — chi tiết qua `GET /employees/{id}`. Click card → mở public profile.

---

## 3. Saved Jobs (`/api/v1/saved-jobs`)

**Auth:** USER trừ `GET /jobs/{jobId}/status` (public).

| # | Method + Path | Mô tả |
|---|---|---|
| 1 | `POST /jobs/{jobId}` | Lưu job — đã lưu rồi → `2031 ALREADY_SAVED_JOB` |
| 2 | `DELETE /jobs/{jobId}` | Bỏ lưu — chưa lưu → `2032 NOT_SAVED_JOB` |
| 3 | `GET /me?page&size` | Danh sách job đã lưu — `PaginatedResult<SavedJobResponse>` |
| 4 | `GET /jobs/{jobId}/status` | Đã lưu chưa? — **không cần đăng nhập** |

`SavedJobResponse` (item list):

```json
{
  "jobId": 12,
  "jobTitle": "Java Developer",
  "companyName": "FPT Software",
  "companySlug": "fpt-software",
  "companyLogoUrl": "...",
  "savedAt": "...",
  "note": null,
  "status": "ACTIVE",
  "expired": false
}
```

- `GET /me` sort `savedAt DESC` (mới lưu trước) — gồm cả job ACTIVE lẫn EXPIRED
- `GET /jobs/{jobId}/status` trả:

```json
{ "isSaved": true }
```

> Nếu chưa đăng nhập hoặc role ≠ USER → `isSaved: false` (không lỗi).

---

## 4. Follows (`/api/v1/follows`)

**Auth:** USER trừ `GET /companies/{companyId}/status` (public).

| # | Method + Path | Mô tả |
|---|---|---|
| 1 | `POST /companies/{companyId}` | Follow — đã follow → `2026 ALREADY_FOLLOWING` |
| 2 | `DELETE /companies/{companyId}` | Unfollow — chưa follow → `2027 NOT_FOLLOWING` |
| 3 | `GET /companies?page&size` | Danh sách công ty đã follow — `PaginatedResult<FollowedCompanyResponse>` |
| 4 | `GET /companies/{companyId}/status` | Trạng thái follow + số follower — **không cần đăng nhập** |

`FollowedCompanyResponse` (item list):

```json
{
  "companyId": 5,
  "companyName": "FPT Software",
  "companySlug": "fpt-software",
  "companyLogoUrl": "...",
  "industry": "IT Outsourcing",
  "city": "HA_NOI",
  "followerCount": 12
}
```

- `GET /companies` sort `followedAt DESC`
- `GET /companies/{companyId}/status` trả:

```json
{
  "followerCount": 12,
  "isFollowing": true
}
```

> Chưa đăng nhập / role ≠ USER → `isFollowing: false` (vẫn trả `followerCount`). Follow/unfollow thành công → server cập nhật `followerCount` trên Company entity (cache).

---

## 5. Error codes

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 404 | `2014 EMPLOYEE_NOT_FOUND` | Chưa có profile employee / profile ẩn |
| 409 | `2031 ALREADY_SAVED_JOB` | Lưu job đã lưu |
| 400 | `2032 NOT_SAVED_JOB` | Bỏ lưu job chưa lưu |
| 409 | `2026 ALREADY_FOLLOWING` | Follow công ty đã follow |
| 400 | `2027 NOT_FOLLOWING` | Unfollow công ty chưa follow |
| 404 | `2033 CERTIFICATE_NOT_FOUND` | Chứng chỉ không tồn tại / không phải của mình |
| 400 | `2040 INVALID_DATE_RANGE` | `startDate` sau `endDate` (experience/education) |
| 403 | `3002 ACCESS_DENIED` | Role không đúng (COMPANY gọi endpoint USER...) |

## 6. FE Handling Suggestions

- **Edit profile:** `GET /employees/me` → fill form → `PUT /employees/me`. Sau khi save, dùng response cập nhật state local ngay (tránh bug "hiển thị data cũ")
- **Skills/Experiences/Education:** gửi **toàn bộ list** (không phải diff) — endpoint ghi đè
- **Certificates:** CRUD từng cái riêng — sau mỗi thao tác refetch list hoặc append/remove local
- **Saved/Follow buttons:** trên job detail / company detail gọi `GET .../status` song song với load data; sau khi toggle → cập nhật local + cập nhật counter nếu có
- **Privacy:** `isPublic = false` → profile ẩn khỏi search + public view; chính user vẫn xem được qua `/me`
