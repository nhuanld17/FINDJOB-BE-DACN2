package com.example.boilerplate.infrastructure.security.jwt;

import com.example.boilerplate.common.constant.JwtConstant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * JwtUtil — Tiện ích trung tâm cho toàn bộ việc tạo và xử lý JWT của hệ thống.
 *
 * Vai trò: cung cấp các thao tác xoay quanh token:
 *  - Generate : tạo AccessToken / RefreshToken cho user đã xác thực.
 *  - Extract  : đọc các claim (username, roles, sessionId, deviceId, jti, expiration) từ token.
 *  - Validate : kiểm tra token còn hạn, đúng chủ sở hữu, tính thời gian sống còn lại.
 *
 * Đặc điểm kỹ thuật:
 *  - Thuật toán ký: HMAC (HS512) — khóa lấy từ config jwt.secret.
 *  - Thư viện: JJWT (io.jsonwebtoken) — parser tự động verify chữ ký khi đọc claim
 *    (nếu sai khóa hoặc token bị sửa → ném exception, không bao giờ trả claim).
 *  - Config lấy từ application.yml: jwt.secret, jwt.access-token-expiration-ms,
 *    jwt.refresh-token-expiration-ms.
 *  - Là @Component → được Spring quản lý, inject singleton vào JwtAuthFilter,
 *    AuthService, TokenBlacklistService,...
 */
@Component
public class JwtUtil {

    /** Khóa bí mật để ký/verify token — đọc từ config jwt.secret. */
    @Value("${jwt.secret}")
    private String secret;

    /** Thời gian sống của AccessToken (milliseconds) — config jwt.access-token-expiration-ms. */
    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    /** Thời gian sống của RefreshToken (milliseconds) — config jwt.refresh-token-expiration-ms. */
    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    // ========== Private ==========

    /**
     * Hàm xây dựng — đóng gói logic chung để tạo một JWT (dùng cho cả Access lẫn Refresh).
     *
     * Token tạo ra có cấu trúc:
     *  - jti (id): UUID ngẫu nhiên — định danh duy nhất cho token, dùng cho
     *    blacklist / session khi cần thu hồi.
     *  - subject: username của user.
     *  - claims: roles (danh sách quyền), sessionId, deviceId.
     *  - iat / exp: thời điểm phát hành / hết hạn.
     *
     * @param userDetails   Thông tin của User đã xác thực (lấy username + roles)
     * @param expirationMs  Thời gian sống của token (milliseconds)
     * @param sessionId     Session Id của Token — dùng để đối chiếu với session trên Redis
     * @param deviceId      ID thiết bị của token — dùng để đối chiếu session theo thiết bị
     * @return Chuỗi token dùng để gắn vào các request để xác thực
     */
    private String buildToken(UserDetails userDetails, long expirationMs, String sessionId, String deviceId) {

        Instant now = Instant.now();

        // Lấy danh sách roles của UserDetails
        List<String> roles = new ArrayList<>();
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            roles.add(authority.getAuthority());
        }

        // Tạo Jti UUID
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .subject(userDetails.getUsername())
                .claim(JwtConstant.CLAIM_ROLES, roles)
                .claim(JwtConstant.CLAIM_SESSION_ID, sessionId)
                .claim(JwtConstant.CLAIM_DEVICE_ID, deviceId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extract claim trong token.
     *
     * Quá trình parse tự động:
     *  1. Verify chữ ký bằng khóa bí mật — token giả / bị chỉnh sửa / ký sai khóa
     *     sẽ ném JwtException ngay tại đây.
     *  2. Kiểm tra format token hợp lệ (3 phần header.payload.signature).
     *  3. Trả về Claims — toàn bộ payload đã giải mã.
     *
     * @param token     AccessToken hoặc RefreshToken
     * @return          Đối tượng Claims chứa các claims trong token
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Lấy khóa ký (signing key) từ chuỗi secret trong config.
     *
     * Chuyển secret sang bytes UTF-8 rồi dùng Keys.hmacShaKeyFor(byte[]) để tạo
     * SecretKey chuẩn HMAC. Thuật toán thực tế (HS256/384/512) do JJWT tự chọn
     * dựa trên độ dài khóa — config hiện tại dùng khóa đủ dài → HS512.
     *
     * Lưu ý: cùng một khóa này được dùng cho cả ký (buildToken) và verify
     * (extractAllClaims) — đảm bảo token tạo ra luôn được xác thực đúng.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // ========== Generate ==========

    /**
     * Tạo AccessToken — token ngắn hạn (jwt.access-token-expiration-ms),
     * đính kèm vào header Authorization của mọi request yêu cầu xác thực.
     *
     * @param userDetails Thông tin user đã xác thực
     * @param sessionId   Session Id của phiên đăng nhập
     * @param deviceId    ID thiết bị đăng nhập
     * @return AccessToken JWT (dạng chuỗi, chưa có prefix "Bearer ")
     */
    public String generateAccessToken(UserDetails userDetails, String sessionId, String deviceId) {
        return buildToken(userDetails, accessTokenExpirationMs, sessionId, deviceId);
    }

    /**
     * Tạo RefreshToken — token dài hạn (jwt.refresh-token-expiration-ms),
     * dùng để xin AccessToken mới khi access cũ hết hạn. Không bao giờ gửi lên
     * cùng các request API thông thường (chỉ dùng ở endpoint refresh-token).
     *
     * @param userDetails Thông tin user đã xác thực
     * @param sessionId   Session Id của phiên đăng nhập
     * @param deviceId    ID thiết bị đăng nhập
     * @return RefreshToken JWT (dạng chuỗi)
     */
    public String generateRefreshToken(UserDetails userDetails, String sessionId, String deviceId) {
        return buildToken(userDetails, refreshTokenExpirationMs, sessionId, deviceId);
    }

    // ========== Extract ==========

    /**
     * Lấy sessionId từ token (claim sessionId).
     * Dùng để đối chiếu phiên đăng nhập với dữ liệu lưu trên Redis.
     *
     * @param token AccessToken hoặc RefreshToken
     * @return sessionId dạng String, hoặc null nếu token không chứa claim này
     */
    public String extractSessionId(String token) {

        Object sessionId = extractAllClaims(token).get(JwtConstant.CLAIM_SESSION_ID);

        return sessionId != null ? sessionId.toString() : null;
        
    }

    /**
     * Lấy deviceId từ token (claim deviceId).
     * Dùng để xác định thiết bị của phiên đăng nhập (quản lý đa thiết bị).
     *
     * @param token AccessToken hoặc RefreshToken
     * @return deviceId dạng String, hoặc null nếu token không chứa claim này
     */
    public String extractDeviceId(String token) {
        Object deviceId = extractAllClaims(token).get(JwtConstant.CLAIM_DEVICE_ID);

        return deviceId != null ? deviceId.toString() : null;
    }

    /**
     * Lấy username của chủ token (trường subject).
     * Dùng để xác định user khi lọc request (JwtAuthFilter) hoặc validate token.
     *
     * @param token AccessToken hoặc RefreshToken
     * @return username đã lưu trong token
     */
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Lấy thời điểm hết hạn của token (trường exp).
     *
     * @param token AccessToken hoặc RefreshToken
     * @return Date đại diện thời điểm token hết hạn
     */
    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    /**
     * Lấy danh sách roles của user từ token (claim roles).
     * Dùng để gán quyền (authorities) cho UsernamePasswordAuthenticationToken
     * trong JwtAuthFilter mà không cần query lại database.
     *
     * @param token AccessToken hoặc RefreshToken
     * @return danh sách roles (VD: ["ROLE_EMPLOYEE"]), rỗng nếu claim không tồn tại
     */
    public List<String> extractRoles(String token) {
        Object rolesObj = extractAllClaims(token).get(JwtConstant.CLAIM_ROLES);
        if (rolesObj instanceof Collection<?> collection) {
            List<String> roles = new ArrayList<>();
            for (Object item : collection) {
                roles.add(String.valueOf(item));
            }
            return roles;
        }
        return List.of();
    }


    /**
     * Lấy jti (JWT ID) của token — định danh duy nhất cho mỗi token.
     * Dùng làm key cho blacklist / thu hồi token (token bị thu hồi được lưu theo jti).
     *
     * @param token AccessToken hoặc RefreshToken
     * @return chuỗi jti (UUID) của token
     */
    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    // ========== Validate ==========

    /**
     * Kiểm tra token có hợp lệ cho một user cụ thể hay không.
     *
     * Chỉ kiểm tra 2 điều kiện nghiệp vụ:
     *  1. Username trong token khớp với username của user truyền vào
     *     (token không bị dùng chéo giữa các tài khoản).
     *  2. Token chưa hết hạn.
     *
     * Lưu ý: chữ ký + format token đã được verify sẵn bên trong
     * extractAllClaims() — nếu token giả/bị sửa, phương thức này sẽ ném
     * exception trước khi đi đến bước so sánh.
     *
     * @param token       Token cần kiểm tra (AccessToken hoặc RefreshToken)
     * @param userDetails User đang xác thực — dùng để so username
     * @return true nếu token đúng chủ sở hữu và còn hạn
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        boolean isExpired = extractExpiration(token).before(new Date());
        return username.equals(userDetails.getUsername()) && !isExpired;
    }

    /**
     * Tính thời gian sống còn lại của token (tính từ thời điểm gọi).
     *
     * Kết quả trả về là giây (đã quy đổi từ millisecond) — cố tình khớp
     * với đơn vị TTL của Redis khi lưu blacklist / session
     * (các hàm revoke*(remainingSeconds, TimeUnit.SECONDS)).
     *
     * @param token Token cần tính (AccessToken hoặc RefreshToken)
     * @return số giây còn lại trước khi token hết hạn (có thể ≤ 0 nếu token đã hết hạn)
     */
    public long remainingTimeOf(String token) {
        long remainingMillis = extractExpiration(token).getTime() - System.currentTimeMillis();
        return remainingMillis / 1000;   // trả giây, khớp revoke*(remainingSeconds, SECONDS)
    }

}
