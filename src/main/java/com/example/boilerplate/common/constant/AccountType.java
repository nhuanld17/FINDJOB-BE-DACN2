package com.example.boilerplate.common.constant;

/**
 * AccountType — "Ý định đăng ký" của user, được lưu trong cột {@code users.pending_account_type}
 * khi user mới đăng ký nhưng CHƯA verify OTP xong.
 *
 * Vì sao cần enum này? Ở thời điểm đăng ký (register), hệ thống CHƯA biết user là ai
 * (chưa có token / session). Để biết họ muốn trở thành người tìm việc hay nhà tuyển dụng,
 * ta hỏi ý định đó ngay trên form đăng ký và "treo" nó lại trong bảng {@code users}
 * (cột {@code pending_account_type}), chờ đến khi verify OTP thành công mới "chốt" role thật.
 *
 * Hai giá trị:
 *  - {@link #USER} — Người tìm việc.
 *    Khi verify OTP xong → được gán role {@code USER} + tự tạo hồ sơ {@code Employee}.
 *  - {@link #EMPLOYER} — Nhà tuyển dụng (đăng ký kèm {@code companyName} bắt buộc).
 *    Khi verify OTP xong → được gán role {@code COMPANY} + tự tạo bản ghi {@code Company}.
 *
 * Quy ước sử dụng trong code (xem {@code AuthServiceImplement}):
 *  1. Register: {@code accountType == null} → coi như {@code USER} (mặc định).
 *     Nếu là {@code EMPLOYER} mà thiếu {@code companyName} → lỗi {@code COMPANY_NAME_REQUIRED}.
 *  2. Verify OTP / login OAuth với tài khoản inactive: đọc {@code pending_account_type},
 *     map sang role {@code RoleEnum.COMPANY} (EMPLOYER) hoặc {@code RoleEnum.USER} (USER),
 *     tạo Company/Employee tương ứng, rồi clear cột pending (intent đã dùng xong).
 *
 * Lưu ý lưu trữ: enum được lưu dạng {@code @Enumerated(EnumType.STRING)}
 * (chuỗi "USER" / "EMPLOYER") trong DB — an toàn hơn lưu ordinal vì thứ tự khai báo
 * có thể thay đổi mà không làm hỏng dữ liệu cũ.
 */
public enum AccountType {
    /** Người tìm việc (role USER, tạo hồ sơ Employee khi kích hoạt). */
    USER,

    /** Nhà tuyển dụng (role COMPANY, tạo Company khi kích hoạt — bắt buộc có companyName). */
    EMPLOYER
}
