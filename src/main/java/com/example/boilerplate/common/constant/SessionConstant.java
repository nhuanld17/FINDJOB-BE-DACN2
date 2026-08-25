package com.example.boilerplate.common.constant;

/**
 * SessionConstant — tập trung toàn bộ tên field + giá trị của session trong Redis.
 *
 * Session được lưu dạng Redis Hash với key {@code session:{sessionId}}
 * (prefix "session:" là private, quản lý trong {@code SessionServiceImpl}).
 * Mỗi field của hash là 1 thông tin của phiên đăng nhập (username, deviceId, status...).
 *
 * Tại sao phải có constant? Vì các field này được ghi/đọc ở nhiều nơi
 * (RedisService — nơi ghi, JwtAuthFilter + AuthServiceImplement — nơi đọc/check).
 * Dùng string trần ở mỗi nơi dễ typo, và nếu đổi tên field ở 1 chỗ sẽ sót chỗ khác
 * → session lệch dữ liệu khó truy vết. Tập trung ở đây giúp đổi tên an toàn.
 */
public final class SessionConstant {
    private SessionConstant() {}

    // =====================
    // FIELD NAMES (key của Redis Hash)
    // =====================

    /** Tên đăng nhập của user — dùng để đối chiếu với claim {@code sub} trong JWT. */
    public static final String USERNAME = "username";

    /** ID thiết bị đăng nhập — dùng để đối chiếu với claim {@code deviceId} trong JWT. */
    public static final String DEVICE_ID = "deviceId";

    /**
     * JTI (ID) của refresh token hiện tại đang được cấp cho session.
     * Field bảo mật then chốt của cơ chế token rotation: mỗi lần refresh,
     * jti mới được ghi đè lên đây — nếu request mang RT cũ (jti không khớp)
     * nghĩa là token bị replay/lộ → hệ thống thu hồi cả session.
     */
    public static final String CURRENT_REFRESH_JTI = "currentRefreshJti";

    /** Trạng thái phiên: {@code ACTIVE} / {@code REVOKED} (xem STATUS_ACTIVE, STATUS_REVOKED). */
    public static final String STATUS = "status";

    /** Thời điểm tạo session (login) — chỉ ghi 1 lần, dùng để thống kê/audit. */
    public static final String CREATED_AT = "createdAt";

    /** Thời điểm hoạt động gần nhất — được cập nhật mỗi lần có request hợp lệ. */
    public static final String LAST_SEEN = "lastSeen";

    /** Tên thiết bị do client gửi lên (vd: "iPhone 15", "Postman Test"). */
    public static final String DEVICE_NAME = "deviceName";

    /** Địa chỉ IP của client lúc đăng nhập. */
    public static final String IP = "ip";

    /** User-Agent của trình duyệt/client lúc đăng nhập. */
    public static final String USER_AGENT = "userAgent";

    // =====================
    // GIÁ TRỊ của field "status"
    // =====================

    /** Session đang hoạt động bình thường. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Session đã bị thu hồi (vd: phát hiện RT reuse, user đổi mật khẩu...). */
    public static final String STATUS_REVOKED = "REVOKED";
}
