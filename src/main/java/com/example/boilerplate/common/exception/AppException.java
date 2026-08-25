package com.example.boilerplate.common.exception;

import com.example.boilerplate.common.constant.ErrorCode;
import lombok.Getter;

/**
 * AppException — exception nghiệp vụ mang theo {@link ErrorCode}.
 *
 * Thiết kế (đã đơn giản hoá): AppException CHỈ dùng message mặc định từ
 * {@link ErrorCode} — KHÔNG còn cơ chế customMessage. Lý do:
 *  - Mọi message phải có 1 nguồn duy nhất (ErrorCode) — dễ quản lý, dễ i18n
 *  - Hết class bug "customMessage bị nuốt": trước đây constructor 2 tham số ghi
 *    message riêng nhưng handler lại đọc {@code ex.getMessage()} (message mặc định)
 *    → message riêng không bao giờ tới client
 *  - Nếu cần message riêng cho tình huống cụ thể → tách ErrorCode mới
 *    (vd: {@code ATS_CV_REQUIRED} thay vì dùng {@code ATS_CV_EMPTY} + custom message)
 *
 * Chi tiết động (exception detail, số liệu cụ thể...) nên log server-side,
 * không đưa vào message trả client (tránh leak thông tin hệ thống).
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Ném lỗi nghiệp vụ — client nhận {@code errorCode.getMessage()} (message mặc định).
     *
     * Ví dụ: {@code throw new AppException(ErrorCode.USER_NOT_FOUND);}
     */
    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
