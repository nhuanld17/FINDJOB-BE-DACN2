package com.example.boilerplate.features.auth.service;

import java.util.Map;
import java.util.Set;

/**
 * SessionService — interface cho toàn bộ thao tác với session trong Redis.
 *
 * Tách riêng (thay vì nhét chung vào RedisService) để đồng bộ với pattern
 * {@link TokenBlacklistService}: mỗi "chủ thể lưu trữ" của auth (session, blacklist)
 * là một service riêng, tự quản lý key prefix của mình. Nhờ đó business layer
 * (AuthServiceImplement, JwtAuthFilter...) chỉ phụ thuộc domain service,
 * không phải chi tiết infrastructure.
 *
 * Key structure (định nghĩa trong impl, private):
 * 
 *   - {@code session:{sessionId}} — Redis Hash chứa toàn bộ field của phiên
 *       (field names lấy từ {@code SessionConstant})
 *   - {@code user:sessions:{userId}} — Redis Set chứa các sessionId đang mở của user
 * 
 */
public interface SessionService {

    /**
     * Tạo session mới (gọi lúc login thành công).
     * Lưu đầy đủ 9 field: username, deviceId, currentRefreshJti, status=ACTIVE,
     * createdAt, lastSeen, deviceName, ip, userAgent.
     * TTL cố định 7 ngày (fixed-window, không gia hạn khi refresh).
     */
    void createSession(
            String sessionId,
            String username,
            String deviceId,
            String currentRefreshJti,
            String deviceName,
            String ip,
            String userAgent
    );

    /**
     * Kiểm tra session có tồn tại trong Redis không (key {@code session:{sessionId}}).
     * Dùng để guard trước khi đọc field — tránh null handling lằng nhằng.
     */
    boolean hasSession(String sessionId);

    /** Lấy 1 field của session hash. Trả {@code null} nếu session/field không tồn tại. */
    Object getSessionField(String sessionId, String field);

    /**
     * Lấy TOÀN BỘ field của session trong 1 lần gọi (HGETALL).
     * Trả về Map RỖNG nếu session không tồn tại (hoặc đã hết TTL).
     */
    Map<Object, Object> getSession(String sessionId);

    /** Ghi đè giá trị 1 field của session hash (field chưa có sẽ được tạo mới). */
    void updateSessionField(String sessionId, String field, Object value);

    /** Đánh dấu session bị thu hồi (status = REVOKED) — không xoá key. */
    void revokeSession(String sessionId);

    /**
     * Lấy TTL còn lại của session (giây). Trả {@code -2} nếu session không tồn tại
     * (quy ước của Redis {@code TTL} command).
     */
    long getSessionTtl(String sessionId);

    /** Xoá hẳn session khỏi Redis. */
    void deleteSession(String sessionId);

    /** Thêm sessionId vào Set {@code user:sessions:{userId}}. */
    void addSessionToUser(String userId, String sessionId);

    /** Lấy danh sách sessionId đang mở của user. */
    Set<Object> getUserSessions(String userId);

    /** Xoá 1 sessionId khỏi Set {@code user:sessions:{userId}}. */
    void removeSessionFromUser(String userId, String sessionId);

    /** Kiểm tra sessionId có nằm trong danh sách session của user không. */
    boolean isUserSession(String userId, String sessionId);

    /** Xoá toàn bộ Set {@code user:sessions:{userId}} (dùng khi xoá hết phiên). */
    void deleteAllUserSessionsIndex(String userId);
}
