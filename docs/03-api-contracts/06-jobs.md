# FE API Contract - Jobs

> **Cập nhật 2026-08-06:** Contract viết theo code hiện tại (`JobController`, `JobService`, `JobQueryDSL`). Gồm 10 endpoint job + 4 endpoint category (`CategoryController`).

## 1. Overview

- **Base path:** `/api/v1/jobs` (category: `/api/v1/categories`)
- **Content type:** `application/json` (trừ ATS scan — multipart, ở contract riêng)
- **Auth:** endpoint COMPANY cần `Authorization: Bearer <accessToken>` + role `COMPANY`

### 1.1 Enum dùng chung

**`JobStatus`**: `ACTIVE`, `EXPIRED`, `CLOSED`, `DRAFT`

| Status | Ý nghĩa |
|---|---|
| `ACTIVE` | Đang tuyển — hiển thị public |
| `EXPIRED` | Hết hạn (quá `expiryDate`) — vẫn hiển thị public để xem/lưu lại |
| `CLOSED` | Đóng tuyển — **KHÔNG** hiển thị public |
| `DRAFT` | Nháp — chỉ COMPANY owner xem |

**`JobType`**: `FULL_TIME`, `PART_TIME`, `CONTRACT`, `INTERNSHIP`, `REMOTE`, `HYBRID`

**`Seniority`**: `INTERN`, `FRESHER`, `JUNIOR`, `MIDDLE`, `SENIOR`, `LEAD`, `MANAGER`

**`City`**: 63 tỉnh/thành — dùng `name()` (VD: `HA_NOI`, `HO_CHI_MINH`, `DA_NANG`) — xem full list trong `City.java`. API chỉ nhận `name()`, không nhận display name ("Hà Nội").

### 1.2 Response format

Success — wrapper `APIResponse`:

```json
{
  "code": 1000,
  "message": "Success",
  "data": { }
}
```

`data` của job là `JobResponse`:

```json
{
  "id": 1,
  "companyId": 5,
  "companyName": "FPT Software",
  "companySlug": "fpt-software",
  "companyLogoUrl": "https://res.cloudinary.com/.../logo.png",
  "createdBy": 12,
  "createdByName": "Nguyễn Văn A",
  "title": "Java Developer",
  "slug": "java-developer",
  "description": "Mô tả công việc...",
  "requirements": "Yêu cầu...",
  "benefits": "Phúc lợi...",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "salaryCurrency": "VND",
  "yearsOfExperience": 2,
  "seniority": "JUNIOR",
  "jobType": "FULL_TIME",
  "location": "Tòa nhà FPT, Cầu Giấy",
  "city": "HA_NOI",
  "skillsRequired": ["Java", "Spring Boot"],
  "expiryDate": "2026-09-01",
  "applyCount": 0,
  "status": "ACTIVE",
  "deleted": false,
  "expired": false,
  "categoryNames": ["Backend", "Java"],
  "createdAt": "2026-08-06T10:00:00Z",
  "updatedAt": "2026-08-06T10:00:00Z"
}
```

Chú thích:
- `salaryMin`/`salaryMax` kiểu số (`BigDecimal`) — null = thoả thuận
- `expired` = `expiryDate < hôm nay` (server tự tính, client không gửi)
- `applyCount` = số đơn ứng tuyển (không đếm đã huỷ)
- `categoryNames` = tên danh mục đã resolve — server tự load, client **chỉ gửi `categoryIds`** (số)

### 1.3 Pagination — 2 loại

**OFFSET (`PaginatedResult`)** — dùng cho public:

```json
{
  "items": [ "JobResponse..." ],
  "page": 0,
  "pageSize": 20,
  "totalItems": 57,
  "totalPages": 3,
  "first": true,
  "last": false,
  "hasNext": true,
  "hasPrevious": false
}
```

**KEYSET (`KeysetPage`)** — dùng cho `GET /jobs/manage`:

```json
{
  "items": [ "JobResponse..." ],
  "nextCursor": "MTc4MzAwMDAwMDAwMDo0Mg",
  "hasMore": true
}
```

> **Keyset là gì?** Thay vì "trang N", client dùng `nextCursor` trả về từ trang trước để lấy trang sau. Cursor = `base64url("epochMillis:id")` của **item cuối cùng** — ổn định khi dữ liệu chèn/xoá giữa chừng (khác OFFSET bị lệch). `nextCursor = null` + `hasMore = false` → hết dữ liệu. **Sort cố định `created_at DESC, id DESC`** — không đổi được.

---

## 2. Endpoint: `POST /api/v1/jobs` — Tạo job (COMPANY)

**Auth:** `hasRole('COMPANY')` — công ty được lấy từ `userId` (JWT), client **không** chọn được companyId.

Request body (`CreateJobRequest`):

```json
{
  "title": "Java Developer",
  "description": "Mô tả công việc chi tiết...",
  "requirements": "Yêu cầu ứng viên...",
  "benefits": "Phúc lợi",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "salaryCurrency": "VND",
  "yearsOfExperience": 2,
  "seniority": "JUNIOR",
  "jobType": "FULL_TIME",
  "location": "Tòa nhà FPT, Cầu Giấy",
  "city": "HA_NOI",
  "skillsRequired": ["Java", "Spring Boot"],
  "expiryDate": "2026-09-01",
  "categoryIds": [1, 3]
}
```

| Field | Bắt buộc | Rule |
|---|---|---|
| `title` | ✅ | `@NotBlank`, max 255 |
| `description` | ✅ | `@NotBlank` |
| `requirements` | ✅ | `@NotBlank` |
| `benefits` | ❌ | |
| `salaryMin`/`salaryMax` | ❌ | số, null = thoả thuận |
| `salaryCurrency` | ❌ | default `"VND"` nếu bỏ trống |
| `yearsOfExperience` | ❌ | `@Min(0)` |
| `seniority` | ✅ | `@NotNull` — enum ở §1.1 |
| `jobType` | ✅ | `@NotNull` |
| `location` | ❌ | max 255 |
| `city` | ✅ | `@NotNull` |
| `skillsRequired` | ❌ | list string |
| `expiryDate` | ✅ | `@NotNull` — **không được ở quá khứ** (lỗi `2029`) |
| `categoryIds` | ❌ | list Long — id phải tồn tại (lỗi `2024`) |

**Hành vi backend:**
- `status` mặc định = `ACTIVE`
- `slug` tự sinh từ title (bỏ dấu tiếng Việt, unique trong công ty — trùng thì thêm `-1`, `-2`...)
- Server tự kiểm tra công ty: `COMPANY_NOT_FOUND` nếu user chưa có công ty

Success: `200` + `APIResponse<JobResponse>` (status = `ACTIVE`).

---

## 3. Endpoint: `GET /api/v1/jobs/{id}` — Chi tiết job (Public)

**Auth:** không cần.

- Trả job nếu `status ∈ {ACTIVE, EXPIRED}` và chưa xoá mềm
- `DRAFT`/`CLOSED` → lỗi `2038 INACTIVE_JOB` (kể cả chủ sở hữu — owner phải dùng `GET /jobs/{id}/owner`)
- Company bị xoá → `2011 COMPANY_NOT_FOUND`

Success: `200` + `APIResponse<JobResponse>`.

---

## 4. Endpoint: `PUT /api/v1/jobs/{id}` — Cập nhật job (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + **chỉ chủ sở hữu** (khác công ty → `3002 ACCESS_DENIED`).

Request body (`UpdateJobRequest`) — **tất cả field optional** (partial update, thiếu field nào giữ nguyên field đó):

```json
{
  "title": "Senior Java Developer",
  "description": "...",
  "requirements": "...",
  "benefits": "...",
  "salaryMin": 25000000,
  "salaryMax": 40000000,
  "salaryCurrency": "VND",
  "yearsOfExperience": 5,
  "seniority": "SENIOR",
  "jobType": "FULL_TIME",
  "location": "...",
  "city": "HA_NOI",
  "skillsRequired": ["Java", "Spring Boot", "Microservices"],
  "expiryDate": "2026-10-01",
  "categoryIds": [1, 2, 3]
}
```

**Quan trọng:**
- `categoryIds` nếu **có mặt** (kể cả `[]`) → xoá toàn bộ category cũ và gắn lại. Nếu **null/thiếu** → giữ nguyên
- Đổi `title` → slug tự regenerate
- `expiryDate` ở quá khứ → lỗi `2029`
- Không có field nào bắt buộc, nhưng gửi hẳn `{}` thì chỉ là no-op

Success: `200` + `APIResponse<JobResponse>`.

---

## 5. Endpoint: `DELETE /api/v1/jobs/{id}` — Xoá job (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + chủ sở hữu.

- **Soft delete** (`deleted = true`) — job không còn xuất hiện ở mọi endpoint public/company nhưng vẫn còn trong DB
- Job không tồn tại / đã xoá → `2017 JOB_NOT_FOUND`

Success: `200` + `APIResponse<Void>` (`data: null`).

---

## 6. Endpoint: `PATCH /api/v1/jobs/{id}/status` — Đổi trạng thái (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + chủ sở hữu.

Request body (chỉ 1 field):

```json
{ "status": "CLOSED" }
```

- `status` ∈ `{ACTIVE, EXPIRED, CLOSED, DRAFT}` — thiếu/blank/sai tên → `1001 BLANK_FIELD`
- Không validate transition (tự do đổi bất kỳ chiều nào)

Success: `200` + `APIResponse<JobResponse>` (trả job sau khi đổi status).

---

## 7. Endpoint: `GET /api/v1/jobs/manage` — Danh sách job của tôi (COMPANY, KEYSET)

**Auth:** `hasRole('COMPANY')` — công ty lấy từ userId.

Query params:

| Param | Optional | Mô tả |
|---|---|---|
| `status` | ✅ | Lọc theo 1 hoặc nhiều status. **Không truyền = tất cả** (ACTIVE/EXPIRED/CLOSED/DRAFT). Truyền nhiều: `status=ACTIVE,DRAFT` hoặc lặp `status=ACTIVE&status=DRAFT`. Sai tên → `1001` |
| `search` | ✅ | Tìm theo title (contains, không dấu) |
| `cursor` | ✅ | Mốc item cuối trang trước (từ `nextCursor`). null = trang đầu |
| `size` | ✅ | 1–100, default 20 (ngoài range bị ép về 20) |

Example:

```
GET /api/v1/jobs/manage?status=ACTIVE,CLOSED&search=java&size=10
GET /api/v1/jobs/manage?cursor=MTc4MzAwMDAwMDAwMDo0Mg
```

**Lưu ý keyset:** sort **cố định** `created_at DESC, id DESC`. Muốn "trang 1" lại từ đầu → gọi không có `cursor`. Cursor sai format → `1001 BLANK_FIELD`.

Success: `200` + `APIResponse<KeysetPage<JobResponse>>`.

---

## 8. Endpoint: `GET /api/v1/jobs/{id}/owner` — Chi tiết job cho owner (COMPANY)

**Auth:** `hasRole('COMPANY')` + chủ sở hữu.

- **Không filter status** — owner xem được cả `DRAFT`/`CLOSED` (khác `GET /jobs/{id}` public)
- Chỉ check: job tồn tại + chưa xoá + đúng công ty

Success: `200` + `APIResponse<JobResponse>`.

---

## 9. Endpoint: `GET /api/v1/jobs/company/{companyId}` — Job của 1 công ty (Public, OFFSET)

**Auth:** không cần.

Query params: `search` (optional, theo title) + `page` (default 0) + `size` (default 20).

- Chỉ trả `status ∈ {ACTIVE, EXPIRED}` — **không bao giờ** DRAFT/CLOSED
- Sort cố định `created_at DESC`
- Công ty không tồn tại → `2011 COMPANY_NOT_FOUND`

Success: `200` + `APIResponse<PaginatedResult<JobResponse>>`.

---

## 10. Endpoint: `GET /api/v1/jobs` — Tìm kiếm job (Public, OFFSET)

**Auth:** không cần.

Query params (tất cả optional):

| Param | Mô tả |
|---|---|
| `search` | Tìm theo title |
| `city` | Enum `City.name()` (VD `HA_NOI`) — sai → 400 |
| `seniority` | Enum `Seniority` (VD `JUNIOR`) |
| `jobType` | Enum `JobType` (VD `FULL_TIME`) |
| `salaryMin` | Lương tối thiểu (Long) |
| `salaryMax` | Lương tối đa (Long) |
| `sort` | `field,direction` — **whitelist**: `title, createdAt, updatedAt, salaryMin, salaryMax, expiryDate, city, seniority, jobType`. Không hợp lệ → mặc định `createdAt,desc`. VD: `sort=salaryMax,asc` |
| `page` | default 0 |
| `size` | default 20 |

Example:

```
GET /api/v1/jobs?search=react&city=HA_NOI&jobType=FULL_TIME&sort=salaryMax,asc&page=0&size=10
```

**Hành vi backend:**
- Luôn lọc: chưa xoá + `status ∈ {ACTIVE, EXPIRED}` (expired vẫn hiện để xem/lưu lại)
- Client không thể ép hiện DRAFT/CLOSED qua param (bảo mật)

Success: `200` + `APIResponse<PaginatedResult<JobResponse>>`.

---

## 11. Categories (`/api/v1/categories`)

> `CategoryController` — **3 endpoint**: GET all, POST tạo (ADMIN), DELETE (ADMIN). Không có GET-by-id / PUT / PATCH. Category là **danh mục nền tảng** (seed sẵn: Backend, Frontend, Java...), không phải tag tự do.

Response shape (`CategoryService.CategoryResponse`):

```json
{
  "id": 1,
  "name": "Backend",
  "slug": "backend",
  "description": "Danh mục Backend"
}
```

### 11.1 `GET /api/v1/categories` — Danh sách (Public)

**Auth:** không cần.

Success: `200` + `APIResponse<List<CategoryResponse>>` (toàn bộ category, không phân trang).

### 11.2 `POST /api/v1/categories` — Tạo (ADMIN)

**Auth:** `hasRole('ADMIN')`.

Request — **query params, KHÔNG phải JSON body**:

```
POST /api/v1/categories?name=Backend&description=Danh mục Backend
```

- `name`: bắt buộc
- `description`: optional
- Slug tự sinh từ name (thường hoá + dấu `-`) — trùng slug → `2025 CATEGORY_ALREADY_EXISTS`

Success: `200` + `APIResponse<CategoryResponse>`.

### 11.3 `DELETE /api/v1/categories/{id}` — Xoá (ADMIN)

**Auth:** `hasRole('ADMIN')`. Không tồn tại → `2024 CATEGORY_NOT_FOUND`.

Success: `200` + `APIResponse<Void>`.

---

## 12. Error codes (Jobs)

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 404 | `2017 JOB_NOT_FOUND` | Job không tồn tại / đã xoá mềm |
| 409 | `2038 INACTIVE_JOB` | Truy cập public job DRAFT/CLOSED |
| 403 | `3002 ACCESS_DENIED` | Không phải chủ sở hữu job/company |
| 404 | `2011 COMPANY_NOT_FOUND` | User chưa có công ty / company bị xoá |
| 400 | `2029 EXPIRY_DATE_IN_PAST` | Ngày hết hạn ở quá khứ |
| 404 | `2024 CATEGORY_NOT_FOUND` | categoryId không tồn tại |
| 409 | `2025 CATEGORY_ALREADY_EXISTS` | Trùng category slug |
| 400 | `1001 BLANK_FIELD` | Thiếu field / status sai / cursor sai format |
| 400 | `1002 OUT_OF_SIZE` | Field vượt max length |
| 400 | `1006`... | Validation khác (`@Min`, enum sai...) |

## 13. FE Handling Suggestions

- **Salary:** `salaryMin`/`salaryMax` null → hiển thị "Thoả thuận"
- **City:** FE hiển thị display name ("Hà Nội") nhưng **gửi lên API phải là `name()`** (`HA_NOI`) — dùng map 2 chiều
- **Category:** khi edit job, load `GET /categories` (tất cả category nền tảng) để render checkbox — `categoryNames` trong response chỉ là để hiển thị
- **Keyset:** lưu `nextCursor`; khi hết (`nextCursor=null, hasMore=false`) → ngừng gọi thêm; khi đổi filter status/search → **reset cursor** (gọi lại từ đầu)
- **Trạng thái job:** item DRAFT hiển thị badge "Nháp", CLOSED "Đã đóng" — owner thấy tất cả, public chỉ thấy ACTIVE/EXPIRED
