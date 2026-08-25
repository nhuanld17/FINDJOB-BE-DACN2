package com.example.boilerplate.common.constant;

/**
 * ApplicationStatus — Vòng đời (lifecycle) của một đơn ứng tuyển (bảng {@code applications}),
 * được lưu trong cột {@code status} dạng {@code @Enumerated(EnumType.STRING)} (chuỗi, max 30 ký tự).
 *
 * Dòng đời điển hình (recruiter duyệt đơn):
 *
 *   Người tìm việc apply         Nhà tuyển dụng duyệt hồ sơ            Quyết định cuối
 *   ──────────────► PENDING ─────► REVIEWING ─► SHORTLISTED ──────► ACCEPTED ✅
 *                                 (đã xem)      (vào danh sách) └──► REJECTED ❌ (kèm lý do)
 *   CANCELLED ◄─────────────────── (chỉ user tự huỷ, chỉ khi còn PENDING)
 *
 * Ý nghĩa từng trạng thái:
 *  - {@link #PENDING} — Vừa nộp đơn, chưa được nhà tuyển dụng xem. Mặc định khi apply.
 *  - {@link #REVIEWING} — Recruiter đang xem xét hồ sơ (gán {@code reviewed_at}).
 *  - {@link #SHORTLISTED} — Ứng viên vào danh sách tiềm năng (gán {@code reviewed_at}).
 *  - {@link #ACCEPTED} — Quyết định cuối: đậu. Gán {@code responded_at} + gửi email
 *    thông báo cho ứng viên. Đã ACCEPTED thì KHÔNG được đổi sang trạng thái khác.
 *  - {@link #REJECTED} — Trượt, bắt buộc có {@code rejectedReason}. Gán {@code responded_at}
 *    + gửi email. Khác ACCEPTED: vẫn có thể revert về trạng thái khác để xem xét lại
 *    (lúc đó {@code rejectedReason} + {@code responded_at} bị xoá).
 *  - {@link #CANCELLED} — Ứng viên tự huỷ đơn, chỉ được phép khi đơn còn {@code PENDING}
 *    (đã được duyệt thì không huỷ được). Recruiter KHÔNG được tự đặt trạng thái này.
 *
 * Các quy tắc quan trọng trong {@code ApplicationService}:
 *  1. Huỷ đơn (user): chỉ khi {@code status == PENDING}, nếu không → {@code APPLICATION_CANNOT_CANCEL}.
 *  2. Đổi status (recruiter): {@code CANCELLED} không hợp lệ → {@code INVALID_APPLICATION_STATUS};
 *     {@code ACCEPTED} là trạng thái cuối, không đổi được nữa → {@code APPLICATION_ALREADY_FINALIZED};
 *     chuyển sang {@code REJECTED} phải kèm {@code rejectedReason} → {@code REJECTED_REASON_REQUIRED}.
 *  3. Sửa CV/coverLetter: chặn khi đơn đã chốt ({@code ACCEPTED} / {@code REJECTED}).
 *  4. Thống kê: {@code CompanyService.getStats} khởi tạo map đếm đủ 6 trạng thái (giá trị 0)
 *     để API luôn trả đủ key kể cả khi chưa có đơn nào ở trạng thái đó.
 */
public enum ApplicationStatus {
    /** Vừa nộp đơn — mặc định, chưa được duyệt. Chỉ ở trạng thái này user mới huỷ đơn được. */
    PENDING,

    /** Recruiter đang xem xét hồ sơ (gán reviewed_at). */
    REVIEWING,

    /** Ứng viên vào danh sách tiềm năng (gán reviewed_at). */
    SHORTLISTED,

    /** Đậu — quyết định cuối cùng, không đổi được nữa (gán responded_at + email thông báo). */
    ACCEPTED,

    /** Trượt — bắt buộc kèm rejectedReason (gán responded_at + email thông báo), có thể revert. */
    REJECTED,

    /** Ứng viên tự huỷ đơn (chỉ khi còn PENDING); recruiter không được đặt trạng thái này. */
    CANCELLED
}
