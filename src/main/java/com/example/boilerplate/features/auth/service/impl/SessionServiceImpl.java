package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.common.constant.SessionConstant;
import com.example.boilerplate.features.auth.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SessionServiceImpl — triển khai toàn bộ thao tác session trên Redis.
 *
 * Tách từ RedisService theo pattern {@code TokenBlacklistServiceImpl}:
 * 
 *   - Key prefix là private — không lộ ra ngoài, nơi khác chỉ dùng qua method.
 *   - Inject RedisTemplate trực tiếp — không đi qua RedisService,
 *       vì RedisService là generic utility, session là domain logic riêng.
 *   - Field names (username, status, currentRefreshJti...) lấy từ {@link SessionConstant}
 *       — public vì JwtAuthFilter/AuthService cần đọc Map trả về.
 * 
 */
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String USER_SESSIONS_PREFIX = "user:sessions:";

    /** TTL session = vòng đời refresh token (7 ngày). Fixed-window: không gia hạn khi refresh. */
    private static final long SESSION_TTL_DAYS = 7L;

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void createSession(
            String sessionId,
            String username,
            String deviceId,
            String currentRefreshJti,
            String deviceName,
            String ip,
            String userAgent
    ) {
        String key = SESSION_KEY_PREFIX + sessionId;

        redisTemplate.opsForHash().put(key, SessionConstant.USERNAME, username);
        redisTemplate.opsForHash().put(key, SessionConstant.DEVICE_ID, deviceId);
        redisTemplate.opsForHash().put(key, SessionConstant.CURRENT_REFRESH_JTI, currentRefreshJti);
        redisTemplate.opsForHash().put(key, SessionConstant.STATUS, SessionConstant.STATUS_ACTIVE);
        redisTemplate.opsForHash().put(key, SessionConstant.CREATED_AT, LocalDateTime.now().toString());
        redisTemplate.opsForHash().put(key, SessionConstant.LAST_SEEN, LocalDateTime.now().toString());
        redisTemplate.opsForHash().put(key, SessionConstant.DEVICE_NAME, deviceName);
        redisTemplate.opsForHash().put(key, SessionConstant.IP, ip);
        redisTemplate.opsForHash().put(key, SessionConstant.USER_AGENT, userAgent);

        redisTemplate.expire(key, SESSION_TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public boolean hasSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public Object getSessionField(String sessionId, String field) {
        String key = SESSION_KEY_PREFIX + sessionId;
        return redisTemplate.opsForHash().get(key, field);
    }

    @Override
    public Map<Object, Object> getSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public void updateSessionField(String sessionId, String field, Object value) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(key, field, value);
    }

    // chưa có caller — giữ lại cho mục đích tương lai (KHÔNG xoá)
    @Override
    public void revokeSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(key, SessionConstant.STATUS, SessionConstant.STATUS_REVOKED);
    }

    @Override
    public long getSessionTtl(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        // getExpire có thể trả null (key không tồn tại) → cố ý đổi về -2 thay vì
        // để NPE khi unboxing sang long (latent bug của RedisService.getTtl cũ).
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null ? ttl : -2L;
    }

    @Override
    public void deleteSession(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
    }

    @Override
    public void addSessionToUser(String userId, String sessionId) {
        String key = USER_SESSIONS_PREFIX + userId;
        redisTemplate.opsForSet().add(key, sessionId);
    }

    @Override
    public Set<Object> getUserSessions(String userId) {
        String key = USER_SESSIONS_PREFIX + userId;
        return redisTemplate.opsForSet().members(key);
    }

    @Override
    public void removeSessionFromUser(String userId, String sessionId) {
        String key = USER_SESSIONS_PREFIX + userId;
        redisTemplate.opsForSet().remove(key, sessionId);
    }

    // chưa có caller — giữ lại cho mục đích tương lai (KHÔNG xoá)
    @Override
    public boolean isUserSession(String userId, String sessionId) {
        String key = USER_SESSIONS_PREFIX + userId;
        Boolean result = redisTemplate.opsForSet().isMember(key, sessionId);
        return Boolean.TRUE.equals(result);
    }

    // chưa có caller — giữ lại cho mục đích tương lai (KHÔNG xoá)
    @Override
    public void deleteAllUserSessionsIndex(String userId) {
        String key = USER_SESSIONS_PREFIX + userId;
        redisTemplate.delete(key);
    }
}
