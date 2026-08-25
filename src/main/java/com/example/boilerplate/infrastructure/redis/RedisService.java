package com.example.boilerplate.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * RedisService — utility generic (không liên quan domain) cho mọi thao tác Redis
 * dùng chung trong dự án: SET/GET/DELETE có TTL, SETNX/SETXX, INCR/DECR, TTL/EXPIRE...
 *
 * Lưu ý kiến trúc: các thao tác mang tính domain (session, blacklist token)
 * KHÔNG nằm ở đây — đã tách ra service riêng để business layer không phụ thuộc
 * chi tiết infrastructure:
 * 
 *   - {@code SessionService} — session:{sessionId} + user:sessions:{userId}
 *   - {@code TokenBlacklistService} — blacklist:refresh:/blacklist:access:
 * 
 */
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Template raw-string riêng cho các key cần lưu plain text (KHÔNG qua JSON wrapper):
     * ví dụ OAuth ticket {@code oauth2:ticket:{uuid}} — được ghi bởi
     * {@code OidcLoginSuccessHandler} bằng StringRedisTemplate.
     * Đọc qua template JSON sẽ không khớp format → phải đọc bằng đúng loại template đã ghi.
     */
    private final StringRedisTemplate stringRedisTemplate;

    // =====================
    // Basic GET / SET / DELETE
    // =====================

    /**
     * Lưu value với TTL.
     * Nếu key đã tồn tại, overwrite và reset TTL.
     */
    public void set(String key, Object value, long ttl, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, ttl, unit);
    }

    /**
     * Lưu value không có TTL (persistent đến khi xóa thủ công).
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * Lấy value theo key, trả {@code null} nếu key không tồn tại.
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Lấy value dạng {@link String}, trả {@code null} nếu key không tồn tại.
     * Tiện hơn {@link #get} khi value luôn là String, không cần cast ở ngoài.
     */
    public String getString(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Xóa một key.
     */
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /**
     * GETDEL — đọc value rồi xóa key trong 1 lệnh (atomic).
     *
     * @return value trước khi xóa; {@code null} nếu key không tồn tại
     *
     * Dùng cho: OAuth ticket single-use — 2 request song song cùng ticket chỉ 1 request
     * lấy được value (chống replay). Dùng {@link StringRedisTemplate} vì ticket được ghi
     * bằng StringRedisTemplate (plain text, không JSON wrapper).
     */
    public String getAndDelete(String key) {
        return stringRedisTemplate.opsForValue().getAndDelete(key);
    }
}
