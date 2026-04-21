package com.example.boilerplate.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

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
     * Xóa nhiều key cùng lúc.
     */
    public void deleteMultiple(String... keys) {
        redisTemplate.delete(List.of(keys));
    }

    // =====================
    // Conditional SET
    // =====================

    /**
     * <b>SETNX</b> — SET chỉ khi key <b>chưa tồn tại</b>.
     * TTL chỉ được set nếu thao tác thành công.
     *
     * @return {@code true} nếu set thành công, {@code false} nếu key đã tồn tại
     *
     * Dùng cho: cooldown, distributed lock — tránh reset TTL khi gọi lại.
     */
    public Boolean setIfAbsent(String key, Object value, long ttl, TimeUnit unit) {
        return redisTemplate.opsForValue().setIfAbsent(key, value, ttl, unit);
    }

    /**
     * <b>SETXX</b> — SET chỉ khi key <b>đã tồn tại</b>.
     *
     * @return {@code true} nếu set thành công, {@code false} nếu key chưa tồn tại
     *
     * Dùng cho: rotate value nhưng chỉ khi session còn sống.
     */
    public Boolean setIfPresent(String key, Object value, long ttl, TimeUnit unit) {
        return redisTemplate.opsForValue().setIfPresent(key, value, ttl, unit);
    }

    // =====================
    // INCREMENT / DECREMENT
    // =====================

    /**
     * <b>INCR</b> — tăng value lên 1, trả về giá trị sau khi tăng.
     * Nếu key chưa tồn tại, khởi tạo = 0 rồi tăng lên 1.
     *
     * Dùng cho: đếm attempts, rate limiting.
     */
    public Long increment(String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    /**
     * <b>INCRBY</b> — tăng value lên {@code delta}, trả về giá trị sau khi tăng.
     */
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * <b>DECR</b> — giảm value xuống 1, trả về giá trị sau khi giảm.
     */
    public Long decrement(String key) {
        return redisTemplate.opsForValue().decrement(key);
    }

    // =====================
    // TTL / EXPIRE
    // =====================

    /**
     * Lấy TTL còn lại của key tính bằng giây.
     *
     * @return TTL còn lại (giây); {@code -1} nếu key tồn tại nhưng không có TTL;
     *         {@code -2} nếu key không tồn tại
     */
    public Long getTtl(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * Gắn TTL vào key đã tồn tại mà không thay đổi value.
     */
    public void expire(String key, long ttl, TimeUnit unit) {
        redisTemplate.expire(key, ttl, unit);
    }

    // =====================
    // KEY CHECK
    // =====================

    /**
     * Kiểm tra key có tồn tại trong Redis không.
     *
     * @return {@code true} nếu key tồn tại, {@code false} nếu không
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
