# Brevo transport — kết quả kiểm chứng sau canary

**Dự án:** FINDJOB-BE | **Ngày:** 2026-09-06 | **Liên quan:** `EMAIL_PERFORMANCE_OPTIMIZATION_PLAN.md` §3
**Bối cảnh:** canary gửi 21 mail test (`OutboxTestService`, danh sách 21 địa chỉ, 20 địa chỉ `@emalupe.com` + 1 `28honest@web-library.net`) qua Brevo HTTP API — hết lỗi 400, Brevo trả 201, người nhận xác nhận đã nhận được mail.

---

## 1. Bằng chứng đã kiểm chứng (nguồn chính thức)

| # | Khẳng định | Bằng chứng |
|---|---|---|
| 1 | API contract: `POST https://api.brevo.com/v3/smtp/email`, header `api-key` + `Content-Type: application/json`, `sender` + `subject` + `to` bắt buộc khi không dùng `templateId` | https://developers.brevo.com/reference/send-transac-email (fetch 2026-09-06). Code `HttpMailSender.java` khớp contract này |
| 2 | Response 201 trả về `messageId`, dùng để tra trạng thái gửi tại Transactional logs: https://app.brevo.com/transactional/email/logs | https://developers.brevo.com/docs/send-a-transactional-email.md (fetch 2026-09-06, mục Response) |
| 3 | Gói Free Brevo: 300 email/ngày, reset hằng ngày, không cộng dồn | https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan (snippet tìm kiếm 2026-09-06) |
| 4 | Trạng thái theo dõi được: Sent, Delivered, Opened, Clicked, Soft Bounce, Hard Bounce, Invalid Email | https://developers.brevo.com/docs/send-a-transactional-email (snippet tìm kiếm 2026-09-06 — xem mục 2) |
| 5 | Về phía code: `EmailService` đã inject `MailSender`; impl duy nhất `HttpMailSender` (Brevo HTTP). Hot path outbox/consumer/reclaimer không đổi | `src/main/java/com/example/boilerplate/infrastructure/mail/EmailService.java`, `HttpMailSender.java` (đọc 2026-09-06) |

## 2. Cập nhật 2026-09-06 (tối) — bằng chứng dashboard sau canary

Người dùng cung cấp ảnh chụp Brevo Transactional logs (ngày 06/09, subject `Test outbox #N`, sender `ledinhnhuan...`):

- **Sent + Delivered** cho các message nhìn thấy (#9, #15, #16, user11–15@emalupe.com), messageId dạng `...@12064225.brev...`, Tags: None → khớp contract 201/`messageId` đã verify ở mục 1. Điểm thiếu trước giờ — "accepted ≠ delivered" — giờ có bằng chứng Delivered thật cho domain `emalupe.com`.
- **First opening / Opened** cho #4, #8 (21:51–21:59) — tracking pixel render trong mail client → bằng chứng mạnh hơn Delivered: mail được mở thật, không nằm yên trong spam.
- Không thấy Soft/Hard Bounce, Blocked, Spam trong phần log nhìn thấy.
- Thời điểm: cả loạt Sent → Delivered trong cùng một phút (21:47) — nhất quán RTT 100–300ms/mail thiết kế trong plan.
- Người dùng xác nhận **21/21 mail nhận được** (xác nhận nhân chủng, bổ sung cho phần log chỉ thấy ~13 event).

**Giới hạn còn lại của bằng chứng:** Delivered của Brevo = máy nhận chấp nhận SMTP, mail vào spam vẫn tính Delivered — với `emalupe.com` điểm này được che bởi event Opened + xác nhận của người; với @gmail.com chưa có dữ liệu.

## 2b. Chưa xác minh được (ghi rõ, không đoán)

- **Thời điểm Brevo trừ quota (khi accepted hay khi delivered)** — không tìm thấy nguồn chính thức nào phát biểu chính xác. Câu "Brevo trừ quota khi chấp nhận gửi" là **suy luận hợp lý** (accepted = Brevo đã nhận hàng gửi), KHÔNG phải fact đã kiểm chứng. Số dư quota thật kiểm tra được trên dashboard Brevo.
- **Inbox placement ở các nhà cung cấp lớn (@gmail.com, @fpt.com.vn...)** — canary 20/21 địa chỉ cùng domain tự quản (`emalupe.com`), đã chứng minh transport + deliverability đến domain đó; chưa có canary Gmail/Outlook.

## 3. Bước xác minh tiếp theo (đề xuất)

1. ~~Mở dashboard logs xác nhận Delivered~~ — **ĐÃ LÀM 2026-09-06 (xem mục 2): Sent + Delivered + Opened xác nhận, không thấy bounce.** Phần còn lại: filter đủ 21 message theo subject `Test outbox` để chốt bằng chứng trọn vẹn nếu cần.
2. Bắn canary tiếp theo gồm ít nhất 1–2 địa chỉ `@gmail.com` thật (inbox người trong nhóm) để test deliverability bên ngoài domain riêng; kiểm tra cả mục Spam.
3. Cân nhắc đăng ký Brevo webhook (`delivered`, `hard_bounce`, `spam`) để tự động đánh dấu trạng thái delivery vào bảng Outbox — thay vì phải vào dashboard xem tay. `SUY LUẬN — cần thiết kế trước khi làm.`
4. Khi bắn thật (OTP, application accepted/rejected), theo dõi quota 300/ngày trên dashboard — đặc biệt ngày cao điểm tuyển dụng.

## 4. Kết luận nhanh

Transport Brevo HTTP API hoạt động thật với ba lớp bằng chứng: (1) code/API contract khớp nguồn chính thức, (2) dashboard logs Sent + Delivered + Opened, (3) xác nhận 21/21 của người nhận. Inbox placement trong `emalupe.com` coi như đã chốt; còn mở: deliverability @gmail.com và cơ chế trừ quota (mục 2b).
