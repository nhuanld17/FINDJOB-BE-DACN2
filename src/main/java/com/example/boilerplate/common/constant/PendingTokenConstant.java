package com.example.boilerplate.common.constant;

/**
 * PendingTokenConstant — tập trung toàn bộ key prefix của pending token trong Redis.
 *
 * Pending token là token tạm (UUID) cấp cho user chưa verify OTP, được ghi/đọc
 * ở nhiều nơi: {@code OtpServiceImpl} (clearAll/clearOtpSessionKeepAttempts) và
 * {@code AuthServiceImplement} (verifyOtp, resendOtp, rotatePendingToken,
 * resolveOrCreatePendingToken...). Dùng string trần {@code "pending:..."} rải rác
 * dễ typo và đổi prefix sót chỗ → gom về đây cho an toàn.
 *
 * 2 key theo cặp:
 * 
 *   - {@code pending:{token}} → userId (map token sang user, dùng để verify)
 *   - {@code pending:user:{userId}} → token (reverse index, dùng để rotate/xóa)
 * 
 * Cả 2 đều có TTL 10 phút ({@code PENDING_TTL_MINUTES} trong AuthServiceImplement).
 */
public final class PendingTokenConstant {
    private PendingTokenConstant() {}

    /** Map token → userId: {@code pending:{token}}. */
    public static final String PREFIX = "pending:";

    /** Reverse index userId → token hiện tại: {@code pending:user:{userId}}. */
    public static final String USER_PREFIX = "pending:user:";
}
