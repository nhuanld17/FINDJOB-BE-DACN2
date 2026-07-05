package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.infrastructure.redis.RedisService;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Giải thích các otp key:
 *
 * 1. otp:{userId} - TTL: 300 giây (5 phút)
 * - Đây là key chứa chính mã OTP mà user phải nhập để verify.
 * - Value cụ thể: chuỗi 6 chữ số, ví dụ "042381".
 * - Giá trị min/max: 000000 đến 999999 (max là 999999).
 * - TTL 5 phút nghĩa là mã OTP chỉ sống 5 phút.
 * - Hết TTL thì mã tự vô hiệu, user bắt buộc phải resend.
 *
 * 2. otp:cooldown:{userId} - TTL: 60 giây
 * - Đây là khóa tạm thời nút resend trong 60 giây sau khi vừa gửi OTP.
 * - Value cụ thể: thường lưu "1" (marker key, chỉ cần biết key còn hay không).
 * - Giá trị max theo thiết kế: 1 (key dạng cờ, không dùng để đếm).
 * - Trong 60 giây này user vẫn nhập OTP và verify bình thường.
 * - Hết 60 giây thì chỉ mở lại quyền bấm resend, không tự làm OTP cũ hết hạn.
 *
 * 3. otp:attempts:{userId} - TTL: 3600 giây (1 giờ)
 * - Không phải đếm số lần nhập sai OTP.
 * - Đây là bộ đếm tổng số lần hệ thống đã phát OTP trong cửa sổ 1 giờ.
 * - Value cụ thể: số nguyên dạng chuỗi, ví dụ "1", "2", "5".
 * - Giá trị max theo rule hiện tại: 5 (đạt >= 5 là bị chặn resend/register inactive flow).
 * - Dùng để chặn spam dài hạn kiểu "mỗi phút gửi lại một lần".
 * - Lần đầu set = 1, các lần sau INCR, và TTL không reset.
 *
 * 4. otp:wrong:{userId} - TTL: 300 giây (5 phút)
 * - Không phải đếm số lần gửi OTP.
 * - Đây là bộ đếm số lần user nhập sai mã OTP.
 * - Value cụ thể: số nguyên dạng chuỗi, ví dụ "0", "1", ..., "5".
 * - Giá trị max theo rule hiện tại: 5 (đạt >= 5 thì chặn verify).
 * - Verify sai thì tăng 1.
 * - Resend thành công thì reset về 0 vì user nhận mã mới.
 * - Tóm lại: wrong = chống brute-force khi nhập OTP.
 *
 * 5. pending:{token} - TTL: 10 phút
 * - Đây là map từ token trong cookie sang userId để server biết đang verify cho user nào.
 * - Value cụ thể: userId dạng số nguyên, ví dụ "123".
 * - Key token cụ thể: UUID, ví dụ pending:550e8400-e29b-41d4-a716-446655440000.
 * - Mỗi lần cấp otp cho client đều reset TTL pending token
 * - Độ dài token: 36 ký tự (UUID chuẩn).
 * - TTL 10 phút nghĩa là phiên OTP tạm thời sống tối đa 10 phút.
 * - Mất key này thì verify/resend đều phải fail vì server không còn biết token thuộc user nào.
 *
 * 6. pending:user:{userId} - TTL: 10 phút
 * - Không phải key bắt buộc để verify mã.
 * - Đây là reverse index để biết user hiện đang có token nào
 * - Value cụ thể: UUID token hiện tại của user, ví dụ "550e8400-e29b-41d4-a716-446655440000".
 * - Giá trị max theo độ dài: 36 ký tự (UUID chuẩn).
 * - Dùng để reuse token nhanh khi cooldown còn hạn và để xóa token cũ khi rotate.
 * - Nhờ key này hệ thống tránh để token cũ rác còn sót.
 *
 *
 */

@Service
@AllArgsConstructor
public class OtpServiceImpl implements OtpService {
    private final StringRedisTemplate redisTemplate;
    private final RedisService redisService;

    public static final long OTP_TTL_SECONDS  = 5 * 60L;  // 5 phút
    public static final long COOLDOWN_SECONDS = 60L;       // 60 giây
    public static final long ATTEMPTS_WINDOW  = 3600L;     // 1 giờ (fixed-window)
    public static final int  MAX_ATTEMPTS     = 5;
    public static final int  MAX_WRONG        = 5;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ───────────────────────── Generate ──────────────────────────────────

    /**
     * Tạo mã OTP 6 số ngẫu nhiên dùng SecureRandom (cryptographically secure).
     * Luôn trả về đúng 6 chữ số, padding "0" ở đầu nếu cần.
     * Ví dụ: 42 -> "000042", 123456 -> "123456".
     */
    @Override
    public String generateOtp() {
        int code = SECURE_RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    // ───────────────────────── Save / Get ────────────────────────────────

    /**
     * Lưu mã OTP vào Redis với TTL 5 phút.
     * Key: otp:{userId}. Ghi đè OTP cũ nếu đã tồn tại.
     */
    @Override
    public void saveOtp(Long userId, String otp) {
        redisTemplate.opsForValue().set(
                "otp:" + userId, otp,
                OTP_TTL_SECONDS, TimeUnit.SECONDS
        );
    }

    /**
     * Lấy mã OTP hiện tại của user từ Redis.
     * Trả về null nếu key không tồn tại hoặc đã hết TTL (OTP expired).
     */
    @Override
    public String getOtp(Long userId) {
        return redisTemplate.opsForValue().get("otp:" + userId);
    }

    /**
     * Xóa OTP của user khỏi Redis.
     * Gọi sau khi verify thành công để đảm bảo OTP không thể dùng lại.
     */
    @Override
    public void deleteOtp(Long userId) {
        redisTemplate.delete("otp:" + userId);
    }

    // ───────────────────────── Cooldown ──────────────────────────────────

    /**
     * Đặt cooldown 60 giây chống resend OTP liên tục.
     * Key: otp:cooldown:{userId}. Khi key này tồn tại, không cho phép resend.
     */
    @Override
    public void setCooldown(Long userId) {
        redisTemplate.opsForValue().set(
                "otp:cooldown:" + userId, "1",
                COOLDOWN_SECONDS, TimeUnit.SECONDS
        );
    }

    /**
     * Lấy TTL còn lại của cooldown (đơn vị: giây).
     * Trả về -2 nếu key không tồn tại (cooldown đã hết).
     */
    @Override
    public long getCooldownTtl(Long userId) {
        Long ttl = redisTemplate.getExpire("otp:cooldown:" + userId, TimeUnit.SECONDS);
        return ttl != null ? ttl : -2L;
    }

    // ───────────────────────── Attempts ──────────────────────────────────

    /**
     * Tăng số lần gửi OTP theo cơ chế fixed-window 1 giờ.
     * - Lần đầu trong window: INCR trả về 1 -> đặt TTL 3600 giây.
     * - Các lần sau: chỉ INCR, KHÔNG reset TTL để giữ đúng fixed-window.
     * Mục đích: ngăn spam kiểu "mỗi phút resend 1 lần mãi mãi".
     */
    @Override
    public long incrementAttempts(Long userId) {
        String key = "otp:attempts:" + userId;

        // Dùng lệnh INCR của redis:
        // Nếu key chưa tồn tại, redis tự tạo key với giá trị là 1
        // Nếu đã tồn tại, nó sẽ tăng giá trị lên 1
        Long current = redisTemplate.opsForValue().increment(key);
        if (current != null && current == 1) {
            redisTemplate.expire(key, ATTEMPTS_WINDOW, TimeUnit.SECONDS);
        }
        return current != null ? current : 1L;
    }

    /**
     * Lấy số lần gửi OTP hiện tại trong fixed-window 1 giờ.
     * Trả về 0 nếu chưa có attempts nào.
     */
    @Override
    public int getAttempts(Long userId) {
        String val = redisTemplate.opsForValue().get("otp:attempts:" + userId);
        return val != null ? Integer.parseInt(val.trim()) : 0;
    }

    /**
     * Lấy TTL còn lại của cửa sổ attempts (đơn vị: giây).
     * Dùng để trả về thời gian chờ cho client khi bị block.
     */
    @Override
    public long getAttemptsTtl(Long userId) {
        Long ttl = redisTemplate.getExpire("otp:attempts:" + userId, TimeUnit.SECONDS);
        return ttl != null ? ttl : -2L;
    }

    /**
     * Kiểm tra user có đang bị block gửi OTP không.
     * Block khi attempts >= 5 trong vòng 1 giờ.
     */
    @Override
    public boolean isAttemptBlocked(Long userId) {
        return getAttempts(userId) >= MAX_ATTEMPTS;
    }

    // ───────────────────────── Wrong ─────────────────────────────────────

    /**
     * Tăng số lần nhập OTP sai của user.
     * Khi đạt MAX_WRONG (5 lần), session OTP sẽ bị hủy.
     */
    @Override
    public long incrementWrong(Long userId) {
        String key = "otp:wrong:" + userId;

        // Tăng số lần nhập OTP sai lên 1.
        // Nếu key chưa tồn tại, Redis sẽ tự tạo key với giá trị 1.
        Long current = redisTemplate.opsForValue().increment(key);

        // Khi key vừa được tạo (current == 1), cần thiết lập TTL.
        // Redis không tự gán TTL cho key được tạo bởi lệnh INCR.
        if (current != null && current == 1) {
            redisTemplate.expire(key, OTP_TTL_SECONDS, TimeUnit.SECONDS);
        }

        return current != null ? current : 1L;
    }

    /**
     * Reset số lần nhập OTP sai về 0.
     * Gọi khi resend OTP mới để user được thử lại từ đầu.
     */
    @Override
    public void resetWrong(Long userId) {
        redisTemplate.opsForValue().set("otp:wrong:" + userId, "0", 5, TimeUnit.MINUTES);
    }

    /**
     * Lấy số lần nhập OTP sai hiện tại.
     * Trả về 0 nếu chưa nhập sai lần nào.
     */
    @Override
    public int getWrong(Long userId) {
        String val = redisTemplate.opsForValue().get("otp:wrong:" + userId);
        return val != null ? Integer.parseInt(val) : 0;
    }

    /**
     * Kiểm tra user có bị block do nhập OTP sai quá nhiều không.
     * Block khi số lần sai >= 5. Khi bị block, session OTP bị hủy
     * và user phải bắt đầu lại flow đăng ký.
     */
    @Override
    public boolean isWrongBlocked(Long userId) {
        return getWrong(userId) >= MAX_WRONG;
    }

    // ───────────────────────── Clear ─────────────────────────────────────

    /**
     * Xóa toàn bộ key OTP liên quan đến user sau khi verify thành công.
     * Các key bị xóa: otp:{userId}, otp:cooldown:{userId}, otp:wrong:{userId},
     * pending:user:{userId}, pending:{token}, otp:attempts:{userId}.
     */
    @Override
    public void clearAll(Long userId) {
        String token = redisService.getString("pending:user:" + userId);

        redisTemplate.delete(List.of(
                "otp:" + userId,
                "otp:cooldown:" + userId,
                "otp:attempts:" + userId,
                "otp:wrong:" + userId
        ));

        redisService.delete("pending:user:" + userId);

        if (token != null && !token.isBlank()) {
            redisService.delete("pending:" + token);
        }
    }

    /**
     * Xóa session OTP khi user nhập sai quá nhiều lần.(resend >= 5)
     * Xóa thêm pending:{token} so với clearAll() để vô hiệu hóa pending token,
     * buộc user phải bắt đầu lại flow đăng ký.
     */
    @Override
    public void clearOtpSessionKeepAttempts(Long userId, String clientToken) {
        // Lấy map token từ userId trong redis
        String mappedToken = redisService.getString("pending:user:" + userId);
        // token chuẩn bị xóa
        String tokenToDelete = null;

        // Nếu map token tồn tại -> thì gán cho tokenToDelete
        if (mappedToken != null && !mappedToken.isBlank()) {
            tokenToDelete = mappedToken;
        }

        // Trong trường hợp key pending:user:{userId} không tồn tại -> sử dụng clientToken
        // từ pendingToken
        else if (clientToken != null && !clientToken.isBlank()) {
            // Lấy ra user id đang sỡ hữu clientToken này
            String ownerId = redisService.getString("pending:" + clientToken);

            // Nếu userId sỡ hữu clientToken và userId của tài khoản đăng kí
            // là cùng 1 người thì xóa -> lưu vào tokenToDelete
            if (ownerId != null && ownerId.equals(userId.toString())) {
                tokenToDelete = clientToken;
            }
        }

        // Xóa session OTP nhưng giữ attempts
        redisTemplate.delete(List.of(
                "otp:" + userId,
                "otp:cooldown:" + userId,
                "otp:wrong:" + userId
        ));
        redisService.delete("pending:user:" + userId);

        if (tokenToDelete != null && !tokenToDelete.isBlank()) {
            redisService.delete("pending:" + tokenToDelete);
        }
    }

    @Override
    public long getOtpTtl(Long id) {
        return redisTemplate.getExpire("otp:" + id) != null ? redisTemplate.getExpire("otp:" + id) : -2L;
    }
}


