# FE API Contract - Applications (Ứng tuyển)

> **Cập nhật 2026-08-06:** Contract viết theo code hiện tại (`ApplicationController`, `ApplicationService`). Gồm 7 endpoint — 4 cho USER (ứng viên), 3 cho COMPANY (nhà tuyển dụng).

## 1. Overview

- **Base path:** `/api/v1/applications`
- **Auth:** mọi endpoint đều cần `Authorization: Bearer <accessToken>` — USER (`hasRole('USER')`) hoặc COMPANY (`hasRole('COMPANY')`)

### 1.1 Enum

**`ApplicationStatus`** (trạng thái ĐƠN): `PENDING`, `REVIEWING`, `SHORTLISTED`, `ACCEPTED`, `REJECTED`, `CANCELLED`

| Status | Ai đặt được | Ý nghĩa |
|---|---|---|
| `PENDING` | Hệ thống khi apply | Mới nộp, chờ xử lý |
| `REVIEWING` | COMPANY | Đang xem xét |
| `SHORTLISTED` | COMPANY | Lọt danh sách |
| `ACCEPTED` | COMPANY | Trúng tuyển — **quyết định cuối** |
| `REJECTED` | COMPANY | Từ chối (bắt buộc `rejectedReason`) |
| `CANCELLED` | ~~(chỉ employee)~~ | Employee huỷ = **xoá hẳn đơn** (không phải set status này) |

> ⚠️ `CANCELLED` **không bao giờ tồn tại trong DB** — employee huỷ đơn là **xoá hẳn record** (endpoint cancel), không set status. COMPANY bị chặn đặt `CANCELLED`.

**`JobStatus`** (trạng thái JOB — dùng trong filter `GET /me`): `ACTIVE`, `EXPIRED`, `CLOSED`, `DRAFT`

### 1.2 Response format

Wrapper chuẩn `APIResponse` (code 1000 / message "Success" / data). Phân trang dùng `PaginatedResult` (xem §1.3 của contract `06-jobs.md`).

---

## 2. USER endpoints

## 2.1 `POST /api/v1/applications/jobs/{jobId}` — Ứng tuyển / cập nhật CV

**Auth:** `hasRole('USER')`

**Content-Type:** `multipart/form-data` (KHÔNG phải JSON)

| Form field | Bắt buộc | Mô tả |
|---|---|---|
| `file` | ❌ | File CV (PDF) — upload Cloudinary. Bỏ trống = đơn không CV |
| `coverLetter` | ❌ | Thư xin việc (string) |

**Hành vi backend:**

| Tình huống | Hành vi |
|---|---|
| Chưa apply job này | Tạo đơn mới `status = PENDING`, upload CV, **`apply_count` +1** |
| Đã apply + đơn còn `PENDING/REVIEWING/SHORTLISTED` | **Cập nhật lại** — upload CV mới (xoá CV cũ), đổi coverLetter. `apply_count` **không** tăng |
| Đã apply + đơn `ACCEPTED/REJECTED` | Lỗi `2039 APPLICATION_ALREADY_FINALIZED` — đơn đã chốt không sửa được |

**Validation khi apply** (`findActiveJobOrThrow`):
- Job không tồn tại / đã xoá mềm → `2017 JOB_NOT_FOUND`
- Job không `ACTIVE` (DRAFT/CLOSED/EXPIRED) → `2018 JOB_ALREADY_CLOSED`
- Job hết hạn (`expiryDate` quá khứ) → `2019 JOB_EXPIRED`
- User chưa có profile employee → `2014 EMPLOYEE_NOT_FOUND`

Success: `200` + `APIResponse<ApplicationResponse>`:

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "id": 55,
    "jobId": 12,
    "jobTitle": "Java Developer",
    "companyId": 5,
    "companyName": "FPT Software",
    "companyLogoUrl": "https://.../logo.png",
    "employeeId": 3,
    "coverLetter": "Tôi mong muốn...",
    "cvUrl": "https://res.cloudinary.com/.../cv.pdf",
    "status": "PENDING",
    "appliedAt": "2026-08-06T10:00:00Z",
    "jobStatus": "ACTIVE",
    "expired": false
  }
}
```

Chú thích: `jobStatus` = trạng thái hiện tại của JOB (để UI phân biệt "đang tuyển"/"hết hạn"); `expired` = job đã quá `expiryDate` chưa (tính theo ngày, kể cả khi scheduler chưa kịp đổi status).

## 2.2 `POST /api/v1/applications/{applicationId}/cancel` — Huỷ ứng tuyển

**Auth:** `hasRole('USER')` + **chỉ chủ sở hữu** đơn (khác user → `2023 APPLICATION_NOT_OWNER`).

**Hành vi:**
- Chỉ huỷ được khi đơn còn `PENDING` — công ty đã xử lý (`REVIEWING/SHORTLISTED/ACCEPTED/REJECTED`) → `2030 APPLICATION_CANNOT_CANCEL`
- Huỷ = **xoá hẳn application khỏi DB** + xoá CV trên Cloudinary + `apply_count` −1 (không xuống dưới 0)

Success: `200` + `APIResponse<Void>` (`data: null`).

## 2.3 `GET /api/v1/applications/jobs/{jobId}/status` — Đã ứng tuyển chưa?

**Auth:** `hasRole('USER')`

Trả về trạng thái ứng tuyển của user hiện tại cho 1 job (dùng cho nút "Ứng tuyển"/"Đã ứng tuyển" trên màn job detail):

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "isApplied": true,
    "applicationId": 55,
    "status": "PENDING",
    "cvUrl": "https://res.cloudinary.com/.../cv.pdf"
  }
}
```

- Chưa apply → `{ "isApplied": false, "applicationId": null, "status": null, "cvUrl": null }`
- `cvUrl` = link CV trong đơn — app dùng để hiển thị nút Xem/Tải CV

## 2.4 `GET /api/v1/applications/me` — Danh sách job đã ứng tuyển

**Auth:** `hasRole('USER')`

Query params:

| Param | Optional | Mô tả |
|---|---|---|
| `jobStatus` | ✅ | Lọc theo trạng thái **JOB** (`ACTIVE`/`EXPIRED`/...) — bỏ trống = tất cả. Dùng cho filter "Tất cả / Đang tuyển / Hết hạn" |
| `page` | ✅ | default 0 |
| `size` | ✅ | default 20 |

- Sort cố định `appliedAt DESC` (mới nhất trước)
- Item = `ApplicationResponse` (§2.1) — **bao gồm cả job ACTIVE lẫn EXPIRED**
- Kết quả = `PaginatedResult<ApplicationResponse>`

---

## 3. COMPANY endpoints

## 3.1 `GET /api/v1/applications/jobs/{jobId}` — Danh sách ứng viên của 1 job

**Auth:** `hasRole('COMPANY')` + **chỉ chủ sở hữu job** (khác → `3002 ACCESS_DENIED`).

Query params:

| Param | Optional | Mô tả |
|---|---|---|
| `status` | ✅ | Lọc theo trạng thái ĐƠN (`PENDING`/`REVIEWING`/...) — bỏ trống = tất cả |
| `page` | ✅ | default 0 |
| `size` | ✅ | default 20 |

- Sort cố định `appliedAt DESC`
- **Quyền riêng tư:** nếu employee `isPublic = false` → `fullName/avatarUrl/email` trả về **null** (chỉ còn `employeeId`)

Item = `ApplicationSummaryResponse`:

```json
{
  "id": 55,
  "status": "PENDING",
  "coverLetter": "...",
  "cvUrl": "https://.../cv.pdf",
  "appliedAt": "2026-08-06T10:00:00Z",
  "employeeId": 3,
  "fullName": "Nguyễn Văn A",
  "avatarUrl": "https://.../avatar.png",
  "email": "a@example.com",
  "isPublic": true
}
```

Kết quả = `PaginatedResult<ApplicationSummaryResponse>`.

## 3.2 `GET /api/v1/applications/{applicationId}` — Chi tiết 1 application

**Auth:** `hasRole('COMPANY')` + chủ sở hữu job.

Trả `ApplicationDetailResponse` — đơn + **hồ sơ đầy đủ ứng viên** + job context:

```json
{
  "id": 55,
  "status": "PENDING",
  "coverLetter": "...",
  "cvUrl": "https://.../cv.pdf",
  "appliedAt": "2026-08-06T10:00:00Z",
  "recruiterNote": "Ghi chú nội bộ",
  "rejectedReason": null,
  "reviewedAt": null,
  "respondedAt": null,
  "employeeId": 3,
  "fullName": "Nguyễn Văn A",
  "avatarUrl": "...",
  "email": "a@example.com",
  "phone": "09xxxxxxxx",
  "title": "Software Engineer",
  "bio": "...",
  "city": "HA_NOI",
  "address": "...",
  "skills": ["Java", "Spring"],
  "experiences": [ "Map<string, object>..." ],
  "education": [ "Map<string, object>..." ],
  "githubUrl": "...",
  "linkedinUrl": "...",
  "portfolioUrl": "...",
  "isPublic": true,
  "jobId": 12,
  "jobTitle": "Java Developer",
  "companyId": 5,
  "companyName": "FPT Software",
  "companyLogoUrl": "..."
}
```

> Nếu employee `isPublic = false` → toàn bộ field cá nhân (`fullName` → `portfolioUrl`) = null. `experiences`/`education` là `List<Map<String,Object>>` (dữ liệu JSON linh hoạt).

## 3.3 `PATCH /api/v1/applications/{applicationId}/status` — Đổi trạng thái đơn

**Auth:** `hasRole('COMPANY')` + chủ sở hữu job.

Request body:

```json
{
  "status": "ACCEPTED",
  "rejectedReason": "Kinh nghiệm chưa phù hợp",
  "recruiterNote": "Ứng viên tiềm năng"
}
```

| Field | Bắt buộc | Rule |
|---|---|---|
| `status` | ✅ | `@NotNull` — enum `ApplicationStatus` |
| `rejectedReason` | **Chỉ khi `status = REJECTED`** | thiếu/blank → `2035 REJECTED_REASON_REQUIRED` |
| `recruiterNote` | ❌ | Ghi chú nội bộ — **luôn update được** kể cả khi không đổi status |

**Business rules:**
- Đơn đã `ACCEPTED` → không đổi được status khác (`2039 APPLICATION_ALREADY_FINALIZED` — quyết định cuối)
- Đơn `REJECTED` → vẫn revert được sang status khác (xoá `rejectedReason` + `respondedAt`)
- Không cho đặt `CANCELLED` → `2034 INVALID_APPLICATION_STATUS`
- Gửi `status` = status hiện tại → chỉ cập nhật `recruiterNote` (không đổi timestamps)
- Tự động set: `reviewedAt` (khi → REVIEWING/SHORTLISTED, chỉ lần đầu), `respondedAt` (khi → ACCEPTED/REJECTED, chỉ lần đầu)
- **Gửi email tự động (async):** `ACCEPTED` → mail "CV đã được duyệt, chờ thông báo từ công ty"; `REJECTED` → mail thông báo từ chối kèm lý do. Email fail **không** rollback việc đổi status

Success: `200` + `APIResponse<ApplicationDetailResponse>` (đơn sau khi cập nhật).

---

## 4. Error codes (Applications)

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 404 | `2021 APPLICATION_NOT_FOUND` | Đơn không tồn tại |
| 403 | `2023 APPLICATION_NOT_OWNER` | Huỷ đơn của người khác |
| 400 | `2030 APPLICATION_CANNOT_CANCEL` | Đơn không còn PENDING nên không huỷ được |
| 409 | `2039 APPLICATION_ALREADY_FINALIZED` | Đơn ACCEPTED/REJECTED — không sửa/đổi status |
| 400 | `2034 INVALID_APPLICATION_STATUS` | Đặt status CANCELLED (chỉ employee huỷ trực tiếp) |
| 400 | `2035 REJECTED_REASON_REQUIRED` | REJECTED mà thiếu lý do |
| 404 | `2014 EMPLOYEE_NOT_FOUND` | User chưa có profile employee |
| 404 | `2017 JOB_NOT_FOUND` | Job không tồn tại / đã xoá |
| 400 | `2018 JOB_ALREADY_CLOSED` | Job không ACTIVE |
| 400 | `2019 JOB_EXPIRED` | Job hết hạn |
| 403 | `3002 ACCESS_DENIED` | COMPANY không phải chủ job |

## 5. FE Handling Suggestions

- **Job detail (USER):** gọi `GET /applications/jobs/{jobId}/status` song song với load job → render nút "Ứng tuyển" hoặc "Đã ứng tuyển" (kèm badge trạng thái + nút Xem/Tải CV)
- **Huỷ đơn:** chỉ hiện nút khi `status = PENDING`; confirm dialog trước khi huỷ (xoá hẳn, không thể hoàn tác)
- **My Applications:** filter chips dùng `jobStatus` param; badge "Hết hạn" dựa vào field `expired` (đáng tin hơn `jobStatus` vì tính theo ngày thật)
- **Company — đổi status:** render menu theo luồng `PENDING → REVIEWING → SHORTLISTED → ACCEPTED/REJECTED`; bắt buộc nhập lý do khi REJECTED; vô hiệu hoá khi đơn đã ACCEPTED
- **Company — list ứng viên:** nếu `isPublic = false`, hiển thị "Hồ sơ riêng tư" thay vì tên/email
