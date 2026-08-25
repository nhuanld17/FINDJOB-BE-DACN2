package com.example.boilerplate.common.constant;

/**
 * OtpConstant — tập trung toàn bộ key prefix của OTP trong Redis.
 *
 * Các key OTP được ghi/đọc rải rác trong {@code OtpServiceImpl} (nhiều method:
 * saveOtp, getOtp, setCooldown, incrementAttempts, incrementWrong, clearAll...).
 * Trước đây mỗi chỗ dùng string trần {@code "otp:..."} → dễ typo, đổi prefix ở
 * 1 chỗ sẽ sót chỗ khác khiến key lệch không truy vết được. Gom hết prefix về
 * đây giúp đổi tên an toàn và nhất quán.
 *
 * Lưu ý: key OTP được lưu bằng {@code StringRedisTemplate} (raw string) vì
 * cần lệnh INCR — không đi qua JSON serializer. Xem {@code OtpServiceImpl}.
 */
public final class OtpConstant {
    private OtpConstant() {}

    /** Key chứa mã OTP hiện tại: {@code otp:{userId}} — TTL 5 phút. */
    public static final String PREFIX = "otp:";

    /** Key cờ chống resend liên tục: {@code otp:cooldown:{userId}} — TTL 60 giây. */
    public static final String COOLDOWN_PREFIX = "otp:cooldown:";

    /** Bộ đếm số lần phát OTP trong cửa sổ 1 giờ: {@code otp:attempts:{userId}}. */
    public static final String ATTEMPTS_PREFIX = "otp:attempts:";

    /** Bộ đếm số lần nhập OTP sai: {@code otp:wrong:{userId}} — TTL 5 phút. */
    public static final String WRONG_PREFIX = "otp:wrong:";
}
