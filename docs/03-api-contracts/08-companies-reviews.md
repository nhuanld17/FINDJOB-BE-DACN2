# FE API Contract - Companies + Reviews

> **Cập nhật 2026-08-06:** Contract viết theo code hiện tại (`CompanyController`, `ReviewController`, `CompanyService`, `ReviewService`). Gồm 8 endpoint company + 5 endpoint review.

## 1. Overview

- **Base path:** `/api/v1/companies` (review: `/api/v1/companies/{companyId}/reviews` + `/api/v1/reviews/{reviewId}`)
- **Content type:** `application/json` (trừ upload logo/cover — multipart)

### 1.1 Response shape — `CompanyResponse` (chi tiết)

```json
{
  "id": 5,
  "ownerId": 12,
  "ownerName": "username_nhuan_123",
  "name": "FPT Software",
  "slug": "fpt-software",
  "description": "...",
  "logoUrl": "https://res.cloudinary.com/.../logo.png",
  "coverUrl": "https://res.cloudinary.com/.../cover.png",
  "website": "https://fpt.com",
  "companySize": "1000-5000",
  "industry": "IT Outsourcing",
  "city": "HA_NOI",
  "address": "...",
  "email": "contact@fpt.com",
  "phone": "02473005555",
  "facebookUrl": "...",
  "linkedinUrl": "...",
  "contactPosition": "HR Manager",
  "followerCount": 12,
  "createdAt": "2026-08-06T10:00:00Z",
  "updatedAt": "2026-08-06T10:00:00Z"
}
```

### 1.2 Response shape — `CompanySummaryResponse` (list/search)

```json
{
  "id": 5,
  "name": "FPT Software",
  "slug": "fpt-software",
  "logoUrl": "...",
  "coverUrl": "...",
  "industry": "IT Outsourcing",
  "companySize": "1000-5000",
  "city": "HA_NOI",
  "website": "...",
  "jobCount": "3",
  "followerCount": 12
}
```

> ⚠️ `jobCount` là **String** (không phải number).

### 1.3 `CompanyStatsResponse` (dashboard)

```json
{
  "totalJobs": 10,
  "activeJobs": 4,
  "totalApplicants": 57,
  "totalFollowers": 12,
  "recentApplications": [
    { "applicationId": 55, "jobTitle": "Java Developer", "employeeName": "Nguyễn Văn A", "avatarUrl": "...", "appliedAt": "..." }
  ],
  "applicationsByStatus": {
    "PENDING": 30, "REVIEWING": 10, "SHORTLISTED": 5, "ACCEPTED": 5, "REJECTED": 7, "CANCELLED": 0
  }
}
```

> `applicationsByStatus` **luôn có đủ 6 key** (khởi tạo 0 cho status không có dữ liệu).

---

## 2. Company endpoints

## 2.1 `GET /api/v1/companies/me` — Công ty của tôi

**Auth:** `isAuthenticated()` — thực tế yêu cầu role `COMPANY` (`getMyCompany` check role → user khác role bị `3002 ACCESS_DENIED`).

- Lấy công ty của user hiện tại (từ JWT)
- User chưa có công ty → `2011 COMPANY_NOT_FOUND`

Success: `200` + `APIResponse<CompanyResponse>`.

## 2.2 `GET /api/v1/companies/{id}` — Chi tiết công ty (Public)

**Auth:** không cần. Company bị xoá mềm / không tồn tại → `2011 COMPANY_NOT_FOUND`.

## 2.3 `GET /api/v1/companies/slug/{slug}` — Chi tiết theo slug (Public)

**Auth:** không cần. Chú ý: `/slug/{slug}` phải đặt **trước** `/{id}` về mặt routing order trong controller (Spring không nhầm vì method khác path pattern).

## 2.4 `PUT /api/v1/companies/{companyId}` — Cập nhật (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + **chỉ owner** (khác → `3002 ACCESS_DENIED`).

Request (`UpdateCompanyRequest`) — **tất cả field optional** (partial):

```json
{
  "name": "FPT Software",
  "description": "...",
  "logoUrl": "https://...", 
  "coverUrl": "https://...",
  "website": "https://fpt.com",
  "companySize": "1000-5000",
  "industry": "IT Outsourcing",
  "city": "HA_NOI",
  "address": "...",
  "email": "contact@fpt.com",
  "phone": "02473005555",
  "facebookUrl": "...",
  "linkedinUrl": "...",
  "contactPosition": "HR Manager"
}
```

- Đổi `name` → slug tự regenerate (trùng slug với công ty khác → thêm `-<epochMillis>`)
- `logoUrl`/`coverUrl` có thể set tay, nhưng khuyến nghị dùng endpoint upload (file → Cloudinary tự động)

## 2.5 `POST /api/v1/companies/{id}/logo` — Upload logo (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + owner.

**Content-Type:** `multipart/form-data` — field bắt buộc `file` (ảnh).

- Upload Cloudinary folder `companies/{id}/logo` (chỉ nhận image types)
- Logo cũ trên Cloudinary **tự xoá** khi upload thành công
- Nếu DB save fail → cleanup file vừa upload (không để orphan)

Success: `200` + `APIResponse<CompanyResponse>` (có `logoUrl` mới).

## 2.6 `POST /api/v1/companies/{id}/cover` — Upload ảnh bìa (COMPANY owner)

Giống upload logo, folder `companies/{id}/cover`.

## 2.7 `DELETE /api/v1/companies/{id}` — Xoá công ty (COMPANY owner)

**Auth:** `hasRole('COMPANY')` + owner.

- **Soft delete** (`deleted = true`) — không xoá dữ liệu job/application
- Công ty đã xoá → các endpoint public trả `2011 COMPANY_NOT_FOUND`

Success: `200` + `APIResponse<Void>`.

## 2.8 `GET /api/v1/companies/me/stats` — Dashboard stats (COMPANY)

**Auth:** `hasRole('COMPANY')`.

- Không có công ty → `2011 COMPANY_NOT_FOUND`
- Không cần param — lấy companyId từ userId

Success: `200` + `APIResponse<CompanyStatsResponse>`.

## 2.9 `GET /api/v1/companies` — Tìm kiếm / danh sách công ty (Public)

Query params:

| Param | Optional | Default | Mô tả |
|---|---|---|---|
| `page` | ✅ | 0 | OFFSET pagination |
| `size` | ✅ | 20 | |
| `search` | ✅ | — | Tìm theo tên |
| `industry` | ✅ | — | Lọc ngành (string) |
| `city` | ✅ | — | Enum `City.name()` |
| `sort` | ✅ | `createdAt,desc` | Spring Data sort string (VD `name,asc`) |

- Không có whitelist sort — **toàn bộ field Company đều sort được** (khác với Jobs)
- Sort sai format → lỗi 400

Success: `200` + `APIResponse<PaginatedResult<CompanySummaryResponse>>`.

---

## 3. Review endpoints

**`ReviewResponse`:**

```json
{
  "id": 1,
  "employeeId": 3,
  "employeeName": "Nguyễn Văn A",
  "avatarUrl": "...",
  "rating": 4,
  "title": "Môi trường tốt",
  "content": "Nội dung đánh giá...",
  "pros": "Đồng nghiệp thân thiện",
  "cons": "Lương chưa cao",
  "createdAt": "...",
  "updatedAt": "..."
}
```

## 3.1 `POST /api/v1/companies/{companyId}/reviews` — Tạo review (USER)

**Auth:** `hasRole('USER')`.

Request (`CreateReviewRequest`):

```json
{
  "rating": 4,
  "title": "Môi trường tốt",
  "content": "Nội dung đánh giá (tối thiểu 10 ký tự)...",
  "pros": "Ưu điểm",
  "cons": "Nhược điểm"
}
```

| Field | Bắt buộc | Rule |
|---|---|---|
| `rating` | ✅ | `@Min(1) @Max(5)` — int |
| `content` | ✅ | `@NotBlank`, min 10, max 5000 |
| `title` | ❌ | max 255 |
| `pros`/`cons` | ❌ | max 2000 |

- **Mỗi employee chỉ review 1 lần / công ty** → `2037 REVIEW_ALREADY_EXISTS`
- Công ty không tồn tại → `2011 COMPANY_NOT_FOUND`
- Sau khi tạo → server cập nhật `average_rating` + `total_reviews` của công ty

## 3.2 `PUT /api/v1/reviews/{reviewId}` — Sửa review (USER)

**Auth:** `hasRole('USER')` + **chỉ chủ sở hữu review**.

- Request = `UpdateReviewRequest` — **tất cả field optional**
- Không tồn tại → `2036 REVIEW_NOT_FOUND`
- Review của người khác → `3002 ACCESS_DENIED`

## 3.3 `DELETE /api/v1/reviews/{reviewId}` — Xoá review (USER)

**Auth:** `hasRole('USER')` + chủ sở hữu. Không tồn tại → `2036`.

## 3.4 `GET /api/v1/companies/{companyId}/ratings` — Tổng quan rating (Public)

**Auth:** không cần.

```json
{
  "averageRating": 4.2,
  "totalReviews": 83,
  "ratingDistribution": { "1": 5, "2": 3, "3": 10, "4": 25, "5": 40 }
}
```

## 3.5 `GET /api/v1/companies/{companyId}/reviews` — Danh sách review (Public)

Query params: `page` (default 0), `size` (default **10**).

- Sort `createdAt DESC` (mới nhất trước)
- Kết quả = `PaginatedResult<ReviewResponse>`

---

## 4. Error codes (Companies & Reviews)

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 404 | `2011 COMPANY_NOT_FOUND` | Công ty không tồn tại / đã xoá / user chưa có công ty |
| 403 | `3002 ACCESS_DENIED` | Không phải owner (update/delete/upload) / role không phải COMPANY |
| 409 | `2037 REVIEW_ALREADY_EXISTS` | Employee đã review công ty này rồi |
| 404 | `2036 REVIEW_NOT_FOUND` | Review không tồn tại |
| 400 | `1001/1002` + bean validation | Thiếu field, rating ngoài 1–5, content < 10 ký tự... |

## 5. FE Handling Suggestions

- **Company profile (owner):** `GET /companies/me` để fill form; upload logo/cover bằng multipart → nhận `CompanyResponse` cập nhật ngay (không cần fetch lại)
- **Company detail (public):** gọi song song `GET /companies/{id}` + `GET /companies/{id}/ratings` + `GET /companies/{id}/reviews` (nếu 1 cái fail, vẫn hiện các phần khác)
- **Review:** user chưa review → hiện form tạo; đã review → hiện review của mình + nút sửa/xoá. Rating hiển thị dùng `ratingDistribution` cho biểu đồ phân bố sao
- **Trạng thái công ty:** `followerCount` cache trên entity — sau khi follow/unfollow nên refetch hoặc cập nhật local
