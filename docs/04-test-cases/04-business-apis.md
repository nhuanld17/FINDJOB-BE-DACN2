# TEST CASES - BUSINESS APIS (tay / Postman)

> **Cập nhật 2026-08-06:** Bộ test tay cho các module nghiệp vụ: **Jobs · Applications · Companies & Reviews · Employees · Saved Jobs · Follows · ATS**.
> Chi tiết request/response/error code: xem `03-api-contracts/` (06 → 10).
> Setup: BE chạy localhost:8080, có token USER + token COMPANY (login qua `POST /api/v1/auth/login`).

## Nhóm 0: Quy ước & Setup

**Token cần chuẩn bị:**
- `TOKEN_USER` — login tài khoản USER (`hasRole('USER')`)
- `TOKEN_COMPANY` — login tài khoản COMPANY (`hasRole('COMPANY')`)
- `TOKEN_ADMIN` — nếu có tài khoản admin (chỉ dùng cho category CRUD)

**Quy ước chung:**
- Header mọi request protected: `Authorization: Bearer <TOKEN>`
- Response chuẩn: `{ code: 1000, message: "Success", data: ... }` — lỗi: `{ status, code, message, errors, timestamp }`
- Bảng tra error code: xem `ErrorCode.java` / phần "Error codes" trong từng contract

---

## Nhóm 1: JOBS (contract 06-jobs.md)

### TC-JB-01: COMPANY tạo job hợp lệ → ACTIVE

**Auth:** `TOKEN_COMPANY`.

```http
POST /api/v1/jobs
Authorization: Bearer <TOKEN_COMPANY>
Content-Type: application/json

{
  "title": "Java Developer",
  "description": "Mô tả công việc",
  "requirements": "Java, Spring Boot",
  "benefits": "13th month",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "salaryCurrency": "VND",
  "yearsOfExperience": 2,
  "seniority": "JUNIOR",
  "jobType": "FULL_TIME",
  "location": "Hà Nội",
  "city": "HA_NOI",
  "skillsRequired": ["Java", "Spring Boot"],
  "expiryDate": "2026-12-31",
  "categoryIds": [1]
}
```

**Kỳ vọng:** `200` + `data.status = "ACTIVE"`, `data.slug` tự sinh, `data.expired = false`. Ghi lại `JOB_ID`.

### TC-JB-02: USER không tạo job được → 403

**Auth:** `TOKEN_USER` + body hợp lệ như TC-JB-01.
**Kỳ vọng:** `403 ACCESS_DENIED` (`3002`).

### TC-JB-03: COMPANY chưa có công ty → COMPANY_NOT_FOUND

**Auth:** COMPANY user chưa verify tạo company. **Kỳ vọng:** `404` code `2011`.

### TC-JB-04: expiryDate ở quá khứ → 2029

**Auth:** `TOKEN_COMPANY`, body TC-JB-01 nhưng `expiryDate: "2020-01-01"`.
**Kỳ vọng:** `400` code `2029 EXPIRY_DATE_IN_PAST`.

### TC-JB-05: categoryIds sai → 2024

**Auth:** `TOKEN_COMPANY`, body TC-JB-01 với `categoryIds: [99999]`.
**Kỳ vọng:** `404` code `2024 CATEGORY_NOT_FOUND`.

### TC-JB-06: Xem job public (ACTIVE) → 200

```http
GET /api/v1/jobs/{JOB_ID}
```
**Kỳ vọng:** `200` + `JobResponse` đầy đủ `categoryNames`.

### TC-JB-07: Xem job DRAFT public → 2038

**Auth:** không cần. **Bước:** COMPANY set status job = DRAFT (TC-JB-11) rồi `GET /jobs/{id}`.
**Kỳ vọng:** `409` code `2038 INACTIVE_JOB`.

### TC-JB-08: Update job (owner) → 200

```http
PUT /api/v1/jobs/{JOB_ID}
Authorization: Bearer <TOKEN_COMPANY>

{ "title": "Senior Java Developer", "salaryMax": 40000000 }
```
**Kỳ vọng:** `200` + `title`/`salaryMax` đổi, slug regenerate.

### TC-JB-09: Update job không phải owner → 403

**Auth:** `TOKEN_COMPANY` khác (công ty khác). **Kỳ vọng:** `403` code `3002`.

### TC-JB-10: Xoá job (soft) → 200, sau đó GET → 2017

```http
DELETE /api/v1/jobs/{JOB_ID}
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `200 data:null`. Sau đó `GET /jobs/{JOB_ID}` → `404` code `2017` (đã xoá mềm).

### TC-JB-11: Đổi status job

```http
PATCH /api/v1/jobs/{JOB_ID}/status
Authorization: Bearer <TOKEN_COMPANY>
Content-Type: application/json

{ "status": "CLOSED" }
```
**Kỳ vọng:** `200` + `data.status = "CLOSED"`. Thử `{ "status": "INVALID" }` → `400` code `1001`.

### TC-JB-12: Keyset pagination GET /jobs/manage

```http
GET /api/v1/jobs/manage?size=2
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `data.items` (2 item), `data.hasMore`, `data.nextCursor` (base64). Gọi lại với `?cursor=<nextCursor>` → trang sau, **không trùng item**, sort `created_at DESC`. Hết dữ liệu → `nextCursor: null, hasMore: false`.

**Sub-case lọc status:** `?status=ACTIVE,DRAFT` → chỉ trả 2 status đó; `?status=ABC` → `400 code 1001`.

### TC-JB-13: Search job public (filter + sort)

```http
GET /api/v1/jobs?search=java&city=HA_NOI&jobType=FULL_TIME&sort=salaryMax,asc&page=0&size=10
```
**Kỳ vọng:** `PaginatedResult`, item chỉ ACTIVE/EXPIRED, sort theo salaryMax tăng dần. Thử `sort=hackedField,asc` → **không lỗi**, fallback `createdAt DESC`.

### TC-JB-14: Categories

- `GET /api/v1/categories` (public) → `200` + list (30 categories seed từ V10)
- `POST /api/v1/categories?name=TestCat` với `TOKEN_USER` → `403`
- `POST /api/v1/categories?name=TestCat` với `TOKEN_ADMIN` → `200` + `CategoryResponse`
- `DELETE /api/v1/categories/{id}` (ADMIN) → `200`; lặp lại → `404` code `2024`

---

## Nhóm 2: APPLICATIONS (contract 07-applications.md)

### TC-AP-01: USER apply job ACTIVE → PENDING

```http
POST /api/v1/applications/jobs/{JOB_ID}
Authorization: Bearer <TOKEN_USER>
Content-Type: multipart/form-data

file=@cv.pdf        (optional)
coverLetter=Giới thiệu bản thân... (optional)
```

**Kỳ vọng:** `200` + `data.status = "PENDING"`, `data.jobId`, `data.cvUrl` (nếu có file). Ghi lại `APP_ID`. `apply_count` của job +1 (xem `GET /jobs/{id}`).

### TC-AP-02: Apply lại job đã apply → update, không lỗi

**Auth:** `TOKEN_USER`, job đã apply ở TC-AP-01 (đơn còn PENDING).
**Kỳ vọng:** `200` — cập nhật CV/coverLetter, `apply_count` **không** tăng.

### TC-AP-03: Apply job CLOSED/EXPIRED → lỗi

**Bước:** job status = CLOSED (TC-JB-11) → apply → `400 code 2018 JOB_ALREADY_CLOSED`. Job quá hạn → `400 code 2019 JOB_EXPIRED`.

### TC-AP-04: Kiểm tra status đã apply

```http
GET /api/v1/applications/jobs/{JOB_ID}/status
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `{ "isApplied": true, "applicationId": <APP_ID>, "status": "PENDING", "cvUrl": "..." }`.

### TC-AP-05: Huỷ đơn khi còn PENDING → 200 (xoá hẳn)

```http
POST /api/v1/applications/{APP_ID}/cancel
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `200`. Sau đó `GET /applications/jobs/{JOB_ID}/status` → `isApplied: false`. `apply_count` −1.

### TC-AP-06: Huỷ đơn đã được xử lý → 2030

**Bước:** tạo đơn mới (TC-AP-01) → COMPANY đổi status thành REVIEWING (TC-AP-10) → user huỷ.
**Kỳ vọng:** `400` code `2030 APPLICATION_CANNOT_CANCEL`.

### TC-AP-07: Danh sách job đã ứng tuyển (USER)

```http
GET /api/v1/applications/me?page=0&size=20
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `PaginatedResult<ApplicationResponse>` — **gồm cả job ACTIVE lẫn EXPIRED** (field `jobStatus` + `expired`). Lọc: `?jobStatus=ACTIVE` → chỉ job ACTIVE.

### TC-AP-08: COMPANY xem ứng viên của job (owner) → 200

```http
GET /api/v1/applications/jobs/{JOB_ID}?status=PENDING&page=0&size=20
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** list `ApplicationSummaryResponse`. Test case private profile: user set `isPublic = false` (TC-EM-08) rồi apply → `fullName/avatarUrl/email = null`, `isPublic = false`.

### TC-AP-09: COMPANY không phải owner → 403

**Auth:** `TOKEN_COMPANY` khác (công ty khác). **Kỳ vọng:** `403` code `3002`.

### TC-AP-10: COMPANY đổi status đơn → REJECTED bắt buộc lý do

```http
PATCH /api/v1/applications/{APP_ID}/status
Authorization: Bearer <TOKEN_COMPANY>
Content-Type: application/json

{ "status": "REJECTED" }
```
**Kỳ vọng:** `400` code `2035 REJECTED_REASON_REQUIRED`.

Gửi kèm lý do:
```json
{ "status": "REJECTED", "rejectedReason": "Kinh nghiệm chưa phù hợp" }
```
**Kỳ vọng:** `200` + `status = "REJECTED"`, `rejectedReason`, `respondedAt` set. **Email thông báo từ chối được gửi** (check inbox user).

### TC-AP-11: Đổi status ACCEPTED → email + không revert được

```http
PATCH /api/v1/applications/{APP_ID}/status
Authorization: Bearer <TOKEN_COMPANY>

{ "status": "ACCEPTED", "recruiterNote": "Ứng viên tốt" }
```
**Kỳ vọng:** `200` + `status = "ACCEPTED"` + **email "CV đã được duyệt, chờ thông báo từ công ty"**. Đổi tiếp sang REVIEWING → `409` code `2039 APPLICATION_ALREADY_FINALIZED`. Đặt `{ "status": "CANCELLED" }` → `400` code `2034`.

### TC-AP-12: Chi tiết đơn (COMPANY)

```http
GET /api/v1/applications/{APP_ID}
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `ApplicationDetailResponse` — hồ sơ đầy đủ ứng viên (skills, experiences, education, cvUrl...).

---

## Nhóm 3: COMPANIES & REVIEWS (contract 08-companies-reviews.md)

### TC-CP-01: COMPANY xem company của mình

```http
GET /api/v1/companies/me
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `200` + `CompanyResponse` (`ownerId` = user). USER gọi → `403` code `3002`.

### TC-CP-02: Cập nhật company

```http
PUT /api/v1/companies/{COMPANY_ID}
Authorization: Bearer <TOKEN_COMPANY>

{ "name": "ACME Corp", "industry": "IT", "city": "HA_NOI", "companySize": "100-500" }
```
**Kỳ vọng:** `200` + slug regenerate theo name mới. User khác → `403`.

### TC-CP-03: Upload logo/cover (multipart)

```http
POST /api/v1/companies/{COMPANY_ID}/logo
Authorization: Bearer <TOKEN_COMPANY>
Content-Type: multipart/form-data

file=@logo.png
```
**Kỳ vọng:** `200` + `logoUrl` mới (Cloudinary). Logo cũ tự xoá.

### TC-CP-04: Xoá company (soft)

```http
DELETE /api/v1/companies/{COMPANY_ID}
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `200`. Sau đó `GET /companies/{id}` → `404` code `2011`. (⚠️ Test cuối nhóm này — công ty bị xoá thì job/application cũng mất hiển thị.)

### TC-CP-05: Dashboard stats

```http
GET /api/v1/companies/me/stats
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `200` + `totalJobs`, `activeJobs`, `totalApplicants`, `totalFollowers`, `recentApplications` (5 mới nhất), `applicationsByStatus` (**đủ 6 key** PENDING..CANCELLED).

### TC-CP-06: Tìm kiếm company (public)

```http
GET /api/v1/companies?search=acme&city=HA_NOI&sort=name,asc&page=0&size=20
```
**Kỳ vọng:** `PaginatedResult<CompanySummaryResponse>` — lưu ý `jobCount` là **String**.

### TC-RV-01: USER review công ty → 200

```http
POST /api/v1/companies/{COMPANY_ID}/reviews
Authorization: Bearer <TOKEN_USER>
Content-Type: application/json

{ "rating": 4, "title": "Môi trường tốt", "content": "Đồng nghiệp thân thiện, học hỏi được nhiều", "pros": "Team hỗ trợ", "cons": "OT ít" }
```
**Kỳ vọng:** `200` + `ReviewResponse`. Review lại → `409` code `2037 REVIEW_ALREADY_EXISTS`.

### TC-RV-02: Rating ngoài 1–5 / content < 10 ký tự → validation

**Kỳ vọng:** `400` (bean validation).

### TC-RV-03: Rating tổng quan + list reviews (public)

```http
GET /api/v1/companies/{COMPANY_ID}/ratings
GET /api/v1/companies/{COMPANY_ID}/reviews?page=0&size=10
```
**Kỳ vọng:** ratings có `averageRating`, `totalReviews`, `ratingDistribution` (5 key 1–5). List sort mới nhất trước, default size **10**.

### TC-RV-04: Sửa/xoá review của mình

```http
PUT /api/v1/reviews/{REVIEW_ID}          # { "rating": 5 } — partial
DELETE /api/v1/reviews/{REVIEW_ID}
```
**Kỳ vọng:** `200`. Xoá review của người khác → `403` code `3002`.

---

## Nhóm 4: EMPLOYEES (contract 09-employees-saved-follows.md)

### TC-EM-01: Xem profile của mình

```http
GET /api/v1/employees/me
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `200` + `EmployeeResponse` (user COMPANY → `404` code `2014`).

### TC-EM-02: Cập nhật profile

```http
PUT /api/v1/employees/me
Authorization: Bearer <TOKEN_USER>
Content-Type: application/json

{ "phone": "09xxxxxxxx", "title": "Software Engineer", "isOpenToWork": true, "isPublic": true, "city": "HA_NOI", "bio": "..." }
```
**Kỳ vọng:** `200` + field mới. Không đụng skills/experiences (endpoint riêng).

### TC-EM-03: Upload avatar (multipart)

```http
POST /api/v1/employees/me/avatar
Authorization: Bearer <TOKEN_USER>
Content-Type: multipart/form-data

file=@avatar.png
```
**Kỳ vọng:** `200` + `avatarUrl` mới.

### TC-EM-04: Cập nhật skills (ghi đè)

```http
PUT /api/v1/employees/me/skills
Authorization: Bearer <TOKEN_USER>
Content-Type: application/json

["Java", "Spring Boot", "PostgreSQL"]
```
**Kỳ vọng:** `200` + `data.skills` = đúng list mới.

### TC-EM-05: Cập nhật experiences (ghi đè)

```http
PUT /api/v1/employees/me/experiences
Authorization: Bearer <TOKEN_USER>

[
  { "company": "FPT", "position": "Dev", "startDate": "2022-01-01", "endDate": "2024-06-30", "isCurrent": false }
]
```
**Kỳ vọng:** `200`. Sub-case `startDate > endDate` → `400` code `2040 INVALID_DATE_RANGE`.

### TC-EM-06: Cập nhật education (ghi đè)

```http
PUT /api/v1/employees/me/education
Authorization: Bearer <TOKEN_USER>

[
  { "school": "ĐH Bách Khoa HN", "degree": "Cử nhân", "major": "CNTT", "startDate": "2016-09-01", "endDate": "2020-06-01", "isCurrent": false }
]
```
**Kỳ vọng:** `200`.

### TC-EM-07: Certificates CRUD

- `GET /me/certificates` → list
- `POST /me/certificates` → `{ "name": "AWS SAA", "issuer": "Amazon", "issueDate": "2025-01-01" }` → `200` + `CertificateResponse`
- `PUT /me/certificates/{certId}` → sửa `name`
- `DELETE /me/certificates/{certId}` → `200`; xoá lần nữa → `404` code `2033`

### TC-EM-08: Public profile + privacy

- `GET /api/v1/employees/{id}` (public, không token) → `200`
- Set `isPublic: false` (PUT /me) → `GET /employees/{id}` → **404** (ẩn); `GET /me` vẫn xem được

### TC-EM-09: COMPANY tìm kiếm ứng viên

```http
GET /api/v1/employees/search?search=java&skills=Java,Spring&isOpenToWork=true&page=0&size=20
Authorization: Bearer <TOKEN_COMPANY>
```
**Kỳ vọng:** `PaginatedResult<CandidateSummaryResponse>` — **chỉ** profile `isPublic = true`, không có email/phone. USER gọi → `403`.

---

## Nhóm 5: SAVED JOBS & FOLLOWS (contract 09-employees-saved-follows.md)

### TC-SV-01: Lưu job → 200; lưu lại → 2031

```http
POST /api/v1/saved-jobs/jobs/{JOB_ID}
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** lần 1 `200`, lần 2 `409` code `2031`.

### TC-SV-02: Status đã lưu chưa (public)

```http
GET /api/v1/saved-jobs/jobs/{JOB_ID}/status
```
**Kỳ vọng:** `{ "isSaved": true }` (không token → `{ "isSaved": false }`).

### TC-SV-03: Danh sách job đã lưu

```http
GET /api/v1/saved-jobs/me?page=0&size=20
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `PaginatedResult<SavedJobResponse>` (có `status`, `expired` — gồm cả job hết hạn).

### TC-SV-04: Bỏ lưu → 200; bỏ nữa → 2032

```http
DELETE /api/v1/saved-jobs/jobs/{JOB_ID}
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** lần 1 `200`, lần 2 `400` code `2032`.

### TC-FW-01: Follow company → 200; follow lại → 2026

```http
POST /api/v1/follows/companies/{COMPANY_ID}
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** lần 1 `200`, lần 2 `409` code `2026`. `followerCount` công ty +1.

### TC-FW-02: Status follow + follower count (public)

```http
GET /api/v1/follows/companies/{COMPANY_ID}/status
```
**Kỳ vọng:** `{ "followerCount": n, "isFollowing": true }` (không token → `isFollowing: false` nhưng vẫn có `followerCount`).

### TC-FW-03: Danh sách công ty đã follow

```http
GET /api/v1/follows/companies?page=0&size=20
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** `PaginatedResult<FollowedCompanyResponse>` (sort `followedAt DESC`).

### TC-FW-04: Unfollow → 200; unfollow nữa → 2027

```http
DELETE /api/v1/follows/companies/{COMPANY_ID}
Authorization: Bearer <TOKEN_USER>
```
**Kỳ vọng:** lần 1 `200`, lần 2 `400` code `2027`. `followerCount` −1.

---

## Nhóm 6: ATS (contract 10-ats.md)

### TC-AT-01: Scan CV với jobId

```http
POST /api/v1/ats/scan
Authorization: Bearer <TOKEN_USER>
Content-Type: multipart/form-data

file=@cv.pdf
jobId={JOB_ID}
```
**Kỳ vọng:** `200` + `AtsResultDto` — `overallScore` (0–100), `matchedSkills`, `missingSkills`, `semanticReasoning`, `tips`, `provider = "groq"`, `model = "llama-3.3-70b-versatile"`, `cached = false`.

### TC-AT-02: Cache HIT — scan lại cùng file + jobId

**Kỳ vọng:** `200` + `cached = true` (trả nhanh, không tốn LLM).

### TC-AT-03: Thiếu jobId lẫn jdText → 2044

**Bước:** gửi chỉ `file`. **Kỳ vọng:** `400` code `2044 ATS_MISSING_INPUT`.

### TC-AT-04: File không đọc được (ảnh scan / sai định dạng) → 2041

**Bước:** gửi file ảnh PNG hoặc PDF trắng. **Kỳ vọng:** `400` code `2041 ATS_CV_EMPTY`.

### TC-AT-05: PDF > 50 trang → 2042

**Kỳ vọng:** `400` code `2042 ATS_CV_TOO_LARGE`.

### TC-AT-06: Scan bằng jdText (không cần jobId)

```http
POST /api/v1/ats/scan
Authorization: Bearer <TOKEN_USER>
Content-Type: multipart/form-data

file=@cv.pdf
jdText=Title: Java Developer ... (tối đa 3000 ký tự)
```
**Kỳ vọng:** `200` + kết quả bình thường.

### TC-AT-07: Groq lỗi → 2043

**Bước:** tắt key/tắt mạng (hoặc đợi rate limit). **Kỳ vọng:** `503` code `2043 ATS_PROVIDER_ERROR` — FE hiện "Dịch vụ AI tạm thời bận".

---

## Phụ lục — Thứ tự test đề xuất (dependency)

1. Login USER + COMPANY → lấy 2 token
2. TC-JB-01 → có `JOB_ID` (dùng cho gần hết nhóm sau)
3. TC-JB-12 (keyset) → cần nhiều job (có thể chạy seed hoặc tạo 3+ job)
4. TC-AP-01 → `APP_ID` → TC-AP-08/10/11/12 (COMPANY xử lý đơn)
5. TC-EM-* → xong trước TC-AP-08 nếu muốn test case profile private
6. TC-RV-* → trước TC-CP-04 (xoá công ty = xoá luôn review)
7. TC-SV-*/TC-FW-* độc lập, làm lúc nào cũng được
8. TC-AT-* → cuối cùng (gọi LLM thật, tốn quota)
