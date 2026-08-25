# FE API Contract - ATS Resume Scoring (AI chấm CV)

> **Cập nhật 2026-08-06:** Contract viết theo code hiện tại (`AtsController`, `AtsScoringService`, `FileParserService`). Dùng Groq LLM (qua Spring AI OpenAI starter) chấm độ khớp CV ↔ JD.

## 1. Overview

- **Endpoint:** `POST /api/v1/ats/scan`
- **Content-Type:** `multipart/form-data`
- **Auth:** `hasRole('USER')` — chỉ ứng viên dùng được

## 2. Request (multipart form-data)

| Form field | Bắt buộc | Mô tả |
|---|---|---|
| `file` | ✅ | File CV — **PDF hoặc DOCX**, max **10 MB** (cấu hình application.yml) |
| `jobId` | ⚠️ 1 trong 2 | ID job — server lấy JD từ DB (title + description + requirements + benefits + seniority + skills + years) |
| `jdText` | ⚠️ 1 trong 2 | JD tự paste, max 3000 ký tự |

> **Bắt buộc `jobId` HOẶC `jdText`** — thiếu cả 2 → `2044 ATS_MISSING_INPUT`. Nếu gửi cả 2 → ưu tiên `jobId`. JD > 3000 ký tự bị cắt cụt.

### File validation (từ `FileParserService`)

| Rule | Giá trị | Lỗi nếu vi phạm |
|---|---|---|
| Định dạng | PDF (.pdf) / DOCX (.docx) — nhận diện qua content-type hoặc extension | `2041 ATS_CV_EMPTY` |
| Kích thước | ≤ 10 MB (Spring multipart cap) | `2042 ATS_CV_TOO_LARGE` |
| Số trang PDF | ≤ 50 trang (chặn DoS — PDF giả mạo nghìn trang) | `2042 ATS_CV_TOO_LARGE` |
| Text tối thiểu | ≥ 100 ký tự (chặn ảnh scan / PDF không có text layer) | `2041 ATS_CV_EMPTY` |
| Text tối đa | Trích ~12 000 ký tự (tránh vượt token window LLM) | — (tự cắt) |

## 3. Response — `AtsResultDto`

```json
{
  "code": 1000,
  "message": "Success",
  "data": {
    "overallScore": 78,
    "matchedSkills": ["Java", "Spring Boot", "REST API"],
    "missingSkills": ["Microservices", "Kafka"],
    "semanticReasoning": "CV có 5/8 kỹ năng chính...",
    "tips": ["Bổ sung Microservices vào phần kỹ năng", "Mô tả rõ hơn dự án deploy"],
    "cvTextLength": 1523,
    "provider": "groq",
    "model": "llama-3.3-70b-versatile",
    "cached": false
  }
}
```

| Field | Mô tả |
|---|---|
| `overallScore` | Điểm 0–100 |
| `matchedSkills` | Kỹ năng CV khớp JD (kỹ năng tương đương như Hibernate ↔ JPA vẫn tính match) |
| `missingSkills` | Kỹ năng JD yêu cầu nhưng CV thiếu |
| `semanticReasoning` | Lý do chấm điểm (tiếng Việt, so sánh kỹ năng tương đương) |
| `tips` | Gợi ý cải thiện CV |
| `cvTextLength` | Độ dài text trích từ CV (debug) |
| `provider` | Luôn `"groq"` |
| `model` | Luôn `"llama-3.3-70b-versatile"` |
| `cached` | `true` nếu kết quả từ Redis cache |

## 4. Cache

- Key = `ats:scan:<SHA-256(cvText + jdText)>` — **cùng CV + cùng JD → cache HIT**
- TTL **24 giờ**
- Cache HIT → trả ngay, không tốn phí LLM, `cached: true`
- Cache parse lỗi → tự xoá key + chấm lại

## 5. Error codes

| HTTP | `code` | Ý nghĩa |
|---|---|---|
| 400 | `2044 ATS_MISSING_INPUT` | Thiếu cả `jobId` lẫn `jdText` |
| 400 | `2041 ATS_CV_EMPTY` | CV không trích được text (chỉ hỗ trợ PDF/DOCX text-based) |
| 400 | `2042 ATS_CV_TOO_LARGE` | File vượt max size (10 MB) |
| 404 | `2017 JOB_NOT_FOUND` | `jobId` không tồn tại / đã xoá |
| 503 | `2043 ATS_PROVIDER_ERROR` | Groq lỗi / quá tải — thử lại sau |
| 500 | `9999 INTERNAL_ERROR` | LLM trả JSON không parse được (sau 1 lần retry) |

> ⚠️ **FE chú ý:** `2043` có thể xảy ra thường xuyên (rate limit Groq) — cần hiện thông báo thân thiện + nút thử lại, không xem là lỗi app.

## 6. FE Handling Suggestions (Android)

1. Chọn file CV (PDF/DOCX, ≤ 10 MB) từ document picker
2. Gửi multipart: `file` + (`jobId` từ job đang xem **hoặc** `jdText` từ form)
3. Loading: LLM mất vài giây → hiển thị spinner + hint "Đang phân tích CV..."
4. Hiển thị kết quả: điểm lớn (0–100) + danh sách matched/missing skills + tips
5. Nếu `cached: true` → có thể hiện badge "Kết quả đã lưu" (không cần thiết)
6. Lỗi `2043` → "Dịch vụ AI tạm thời bận, vui lòng thử lại sau"

---

## 7. Phụ lục — `GET /api/v1/test/ping`

Health check đơn giản (`PingController`):

```json
{ "code": 1000, "message": "Success", "data": "pong" }
```

Không cần auth — dùng để kiểm tra server còn sống / setup app.
