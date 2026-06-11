package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.AuthResponse;
import com.example.boilerplate.features.auth.service.AuthService;
import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.features.auth.service.TokenBlacklistService;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.RoleRepository;
import com.example.boilerplate.features.user.repository.UserRepository;
import com.example.boilerplate.infrastructure.mail.EmailService;
import com.example.boilerplate.infrastructure.redis.RedisService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import com.example.boilerplate.infrastructure.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImplement implements AuthService {

    private static final long PENDING_TTL_MINUTES = 10L;

    private final RedisService redisService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final OtpService otpService;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    @Transactional
    public void register(RegisterRequest request, HttpServletResponse response, String pendingToken) {

        // Kiểm tra mật khẩu và mật khẩu xác nhận có khớp không
        if (!request.password().trim().equals(request.confirmPassword().trim())) {
            throw new AppException(ErrorCode.PASSWORD_MISMATCH);
        }

        // Email đã được dùng bởi tài khoản đang hoạt động
        if (userRepository.isEmailAlreadyInUse(request.email().trim().toLowerCase())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_IN_USE);
        }

        // Email thuộc tài khoản bị khóa
        if (userRepository.isEmailBanned(request.email().trim().toLowerCase())) {
            throw new AppException(ErrorCode.ACCOUNT_BANNED);
        }

        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        // Username đã được dùng bởi tài khoản khác
        if (userRepository.isUserNameAlreadyInUse(username, email)) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_IN_USE);
        }

        User user;

        // Nếu email thuộc tài khoản chưa kích hoạt thì cập nhật lại thông tin
        if (userRepository.isInactiveAccount(email)) {
            user = userRepository.findByEmail(email).orElseThrow(
                    () -> new AppException(ErrorCode.EMAIL_ALREADY_IN_USE)
            );

            user.setUsername(username);
            user.setFullName(request.fullName().trim());
            user.setPassword(passwordEncoder.encode(request.password().trim()));
            user.setActive(false);
            user.setDeleted(false);
            userRepository.save(user);
        }
        // Nếu chưa có tài khoản thì tạo mới
        else  {
            user = new User();
            user.setUsername(username);
            user.setFullName(request.fullName().trim());
            user.setPassword(passwordEncoder.encode(request.password().trim()));
            user.setEmail(email);
            user.setActive(false);
            user.setDeleted(false);
            userRepository.save(user);
        }

        // ======== Tạo và gửi OTP ========

        /**
         * Nếu số lần gửi OTP trong cửa sổ 1 giờ đã chạm ngưỡng 5:
         * - Block resend otp, nhưng vẫn giữ key redis trong trường
         * hợp người dùng vẫn còn lượt nhập
         */

        if (otpService.getAttempts(user.getId()) >= 5) {
            // otpService.clearOtpSessionKeepAttempts(user.getId(), pendingToken);
            // clearPendingCookie(response);
            throw new AppException(ErrorCode.OTP_SEND_LIMIT_REACHED);
        }

        // Lấy thời gian cooldown còn lại (đơn vị giây)
        long cooldownTTl = otpService.getCooldownTtl(user.getId());

        // Nếu còn cooldown thì không tạo OTP mới, chỉ gia hạn và trả lại pending token
        if (cooldownTTl > 0) {
            // Get pending token from redis and send to client, while also renewing
            // TTL for both pending token and reverse token
            String token = resolveOrCreatePendingToken(user.getId(), pendingToken);

            // After renewing TTL for pending token in redis, create pending token in
            // cookie and attach it to the response
            writePendingCookie(response, token);

            return;
        }

        /**
         * Khi đã hết cooldown và chưa vượt ngưỡng attempts:
         * - Cấp/rotate pending token mới
         * - Tạo OTP mới và lưu vào Redis
         * - Cập nhật cooldown, attempts, wrong
         * - Gửi OTP qua email
         */
        String token = rotatePendingToken(user.getId());
        writePendingCookie(response, token);

        // Create otp code and update guard state
        String otp = otpService.generateOtp();

        // Save otp code
        otpService.saveOtp(user.getId(), otp);

        // set cool down 60s after send otp to client
        otpService.setCooldown(user.getId());

        // increase attempt one unit
        otpService.incrementAttempts(user.getId());

        // Reset the number of incorrect OTP attempts to 0.
        otpService.resetWrong(user.getId());

        // Gửi emai thông báo
        emailService.sendOtpEmail(email, username, otp);
    }

    @Override
    @Transactional
    public void verifyOtp(VerifyOtpRequest request, String pendingToken, HttpServletResponse httpServletResponse) {

        // Kiểm tra pendingToken từ Cookie, nếu không có token, phiên verify đã hết hạn
        if ( pendingToken == null || pendingToken.isBlank()) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        // Kiểm tra pending:{token} để lấy userId
        // Nếu không tra ra userId: token đã hết hạn hoặc không hợp lệ. Xóa cookie
        // Pending rồi trả về OTP_VERIFICATION_SESSION_EXPIRED
        String userId = redisService.getString("pending:" + pendingToken);
        if (userId == null || userId.isBlank()) {
            clearPendingCookie(httpServletResponse);
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        Long uid;

        try {

            uid = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            // nếu userId không hợp lệ thì xóa pendingCoookie
            // Nhưng ko xóa các key redis khác vì chưa xác minh được user là ai
            clearPendingCookie(httpServletResponse);
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        // Nếu số lần gửi OTP đã vượt quá ngưỡng 5 trong cửa sổ 1 giờ thì chặn
        // resend , những vẫn giữ key redis trong trường hợp người dùng vẫn
        // còn lượt nhập otp
        if (otpService.getAttempts(uid) > 5) {
            // Xóa các key redis, chỉ giữ lại otp:attempts:{userId} để nếu user đăng kí lại
            // thì có thể đối chiếu để chặn gửi otp
            // otpService.clearOtpSessionKeepAttempts(uid, pendingToken);
            // Xóa pending cookie ở phía client
            // clearPendingCookie(httpServletResponse);

            throw new AppException(ErrorCode.OTP_VERIFY_LIMIT_REACHED);
        }

        // Nếu số lần nhập sai hiện tại quá 5 lần thì block
        if (otpService.getWrong(uid) >= 5) {
            throw new AppException(ErrorCode.MAX_WRONG_OTP);
        }

        String otp = otpService.getOtp(uid);

        // Kiểm tra otp còn hạn hay không
        if (otp == null || otp.isBlank()) {
            throw new AppException(ErrorCode.OTP_EXPIRED);
        }

        // Kiểm tra dữ liệu otp từ request trước khi so sánh
        if (request == null || request.otp() == null || request.otp().isBlank()) {
            throw new AppException(ErrorCode.OTP_INVALID);
        }

        // Nếu otp từ request không đúng thì tăng wrong counter
        // và trả lỗi OTP invalid
        if (!otp.equals(request.otp().trim())) {
            otpService.incrementWrong(uid);
            throw new AppException(ErrorCode.OTP_INVALID);
        }

        // Nếu otp đúng thì đổi trạng thái của user sang activate=true
        User user = userRepository.findByIdWithRoles(uid).orElseThrow(
                () -> new AppException(ErrorCode.USER_NOT_FOUND)
        );

        // Gán ROLE_USER mặc định khi verify thành công (nếu user chưa có role này)
        boolean hasUserRole = user.getRoles().stream()
            .anyMatch(role -> role.getName() == RoleEnum.USER);
        if (!hasUserRole) {
            var defaultRole = roleRepository.findByName(RoleEnum.USER)
                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_ERROR));
            user.getRoles().add(defaultRole);
        }

        user.setActive(true);
        userRepository.save(user);

        // Dọn state otp sau khi verify thành công
        otpService.clearAll(user.getId());
        // Extra safety: ensure current pending token key is removed even if reverse index was stale.
        redisService.delete("pending:" + pendingToken);
        redisService.delete("pending:user:" + uid);
        // Xóa pending cookie ở client
        clearPendingCookie(httpServletResponse);
    }

    @Override
    public void resendOtp(String pendingToken, HttpServletResponse httpServletResponse) {
        // Nếu pendingToken từ request không có => Hết phiên OTP
        if (pendingToken == null || pendingToken.isBlank()) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        Long uid;

        // Lấy userId của pendingToken hiện tại
        try {
            String userId = redisService.getString("pending:" + pendingToken);

            if (userId == null || userId.isBlank()) {
                clearPendingCookie(httpServletResponse);
                throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
            }

            uid = Long.parseLong(userId);

        } catch (NumberFormatException e) {
            clearPendingCookie(httpServletResponse);
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        // Kiểm tra cooldown, nếu còn cooldown thì chặn resend
        if (otpService.getCooldownTtl(uid) > 0) {
            throw new AppException(ErrorCode.COOLDOWN_ACTIVE);
        }

        // Kiểm tra attempts
        if (otpService.getAttempts(uid) >= 5) {
//            clearPendingCookie(httpServletResponse);
//            otpService.clearOtpSessionKeepAttempts(uid, pendingToken);
            throw new AppException(ErrorCode.OTP_SEND_LIMIT_REACHED);
        }

        // Tạo mã otp mới
        String otp = otpService.generateOtp();

        // Reset TTL của otp:{userId} với TTL 5 phút
        otpService.saveOtp(uid, otp);
        // Set cooldown cho otp hiện tại về 60s
        otpService.setCooldown(uid);
        // Reset wrong về 0
        otpService.resetWrong(uid);
        // Tăng số lần gửi otp thêm 1
        otpService.incrementAttempts(uid);

        // Sau khi có otp mới thì rotate pendingToken để đồng bộ Redis + cookie
        String newPendingToken = rotatePendingToken(uid);
        writePendingCookie(httpServletResponse, newPendingToken);

        // Gửi email cho user
        User user = userRepository.findById(uid).orElseThrow(
                () -> new AppException(ErrorCode.USER_NOT_FOUND)
        );

        emailService.sendOtpEmail(user.getEmail(), user.getUsername(), otp);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletResponse httpServletResponse, String pendingToken) {

        // Chuẩn hóa email và password
        String email = request.email().trim().toLowerCase();
        String password = request.password().trim();

        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                new UsernamePasswordAuthenticationToken(email, password);

        try {
            Authentication authenticatedToken = authenticationManager.authenticate(usernamePasswordAuthenticationToken);

            // Đặt authentication vào securitycontext
            SecurityContextHolder.getContext().setAuthentication(authenticatedToken);

        } catch (DisabledException e) {
            handleInactiveUserLogin(email, httpServletResponse, pendingToken);
            throw new AppException(ErrorCode.USER_INACTIVE);

        } catch (LockedException e) {
            throw new AppException(ErrorCode.ACCOUNT_BANNED);

        } catch (BadCredentialsException e) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);

        } catch (AuthenticationException e) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new AppException(ErrorCode.USER_NOT_FOUND)
        );

        UserDetails userDetails = (UserDetails) SecurityContextHolder.getContext()
                                    .getAuthentication().getPrincipal();

        // Tạo accessToken và refreshToken dựa trên thông tin của User
        String accessToken = jwtUtil.generateAccessToken(userDetails);
        String refreshToken = jwtUtil.generateRefreshToken(userDetails);

        // Lưu refreshtoken vào redis
        String refreshKey = "auth:refresh:" + user.getId();
        redisService.set(refreshKey, refreshToken, 7, TimeUnit.DAYS);

        // Thêm refreshToken vào cookie HttpOnly
        writeCookie(
                httpServletResponse,
                "refreshToken",
                refreshToken,
                true,
                true,
                "/api/v1/auth/refresh-token",
                "Strict",
                7 * 24 * 60 * 60L
        );

        // trả về thông tin của user (jti, username, roles, accesstoken)
        return new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getRoles(),
                accessToken
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refreshToken(String refreshToken, HttpServletResponse httpServletResponse) {
        if (refreshToken == null || refreshToken.isBlank()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String email;
        String jti;
        try {
            email = jwtUtil.extractUsername(refreshToken);
            jti = jwtUtil.extractJti(refreshToken);
        } catch (Exception e) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // kiểm tra AT đã bị thu hồi hay chưa
        if (tokenBlacklistService.isRefreshTokenRevoked(jti)) {
            throw new AppException(ErrorCode.TOKEN_REVOKED);
        }

        User user = userRepository.findByEmailWithRoles(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (user.isDeleted()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.ACCOUNT_BANNED);
        }

        if (!user.isActive()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.USER_INACTIVE);
        }

        // UserDetails userDetails = new CustomUserDetails(user);
        CustomUserDetails userDetails = new CustomUserDetails(user);

        if (!jwtUtil.isTokenValid(refreshToken, userDetails)) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String refreshKey = "auth:refresh:" + user.getId();
        String storedRefreshToken = redisService.getString(refreshKey);

        if (storedRefreshToken == null || !storedRefreshToken.equals(refreshToken)) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // Cấp access token mới
        String newAccessToken = jwtUtil.generateAccessToken(userDetails);

        // Rotate refresh token để đồng bộ TTL Redis với exp bên trong JWT
        String newRefreshToken = jwtUtil.generateRefreshToken(userDetails);
        redisService.set(refreshKey, newRefreshToken, 7, TimeUnit.DAYS);

        // ghi refreshToken vào cookie
        writeCookie(
                httpServletResponse,
                "refreshToken",
                newRefreshToken,
                true,
                true,
                "/api/v1/auth/refresh-token",
                "Strict",
                7 * 24 * 60 * 60L
        );

        return new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getRoles(),
                newAccessToken
        );
    }

    /**
     * Logout is implemented as server-side refresh token revocation:
     * - Always clears refreshToken cookie (idempotent).
     * - If refreshToken is present and valid, delete the stored refresh token in Redis.
     *
     * Access tokens are stateless JWTs; we do not keep a blacklist here.
     */
    @Override
    public void logout(String refreshToken, HttpServletRequest request, HttpServletResponse response) {

        // Always clear cookie even if token is missing/invalid.
        clearRefreshTokenCookie(response);

        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        String authorizationHeader = request.getHeader("Authorization");

        // If no token -> skip, let the next filter process
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return;
        }

        String accessToken = authorizationHeader.substring(7);

        String email;
        String jtiRefreshToken;
        long remainingRefreshToken;
        String jtiAccessToken;
        long remainingAccessToken;

        try {
            jtiRefreshToken = jwtUtil.extractJti(refreshToken);
            jtiAccessToken = jwtUtil.extractJti(accessToken);
            remainingAccessToken = jwtUtil.remainingTimeOf(accessToken);
            remainingRefreshToken = jwtUtil.remainingTimeOf(refreshToken);
            email = jwtUtil.extractUsername(refreshToken);
        } catch (Exception e) {
            // Malformed/expired/invalid signature -> still treat as logged out.
            return;
        }

        // lưu Access Token và Refresh Token vào blacklist
        tokenBlacklistService.revokeAccessToken(jtiAccessToken, remainingAccessToken);
        tokenBlacklistService.revokeRefreshToken(jtiRefreshToken, remainingRefreshToken);

        userRepository.findByEmail(email).ifPresent(user -> {
            String refreshKey = "auth:refresh:" + user.getId();
            redisService.delete(refreshKey);
        });
    }

    private void writeCookie(
            HttpServletResponse response,
            String name,
            String value,
            boolean httpOnly,
            boolean secure,
            String path,
            String sameSite,
            long maxAgeSeconds
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAgeSeconds)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void handleInactiveUserLogin(String email, HttpServletResponse httpServletResponse, String pendingToken) {
        // Tìm user theo email
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Long userId = user.getId();

        // Kiểm tra attempts: Nếu đã đạt 5 lần gửi OTP thì chặn gửi mới
        if (otpService.getAttempts(userId) >= 5) {
            throw new AppException(ErrorCode.OTP_SEND_LIMIT_REACHED);
        }

        // 2) Check cooldown sau
        long cooldownTtl = otpService.getCooldownTtl(userId);
        if (cooldownTtl > 0) {
            String token = resolveOrCreatePendingToken(userId, pendingToken);
            writePendingCookie(httpServletResponse, token);
            return;
        }

        // 3) Không bị block thì phát OTP mới
        String token = rotatePendingToken(userId);
        writePendingCookie(httpServletResponse, token);

        String otp = otpService.generateOtp();
        otpService.saveOtp(userId, otp);
        otpService.setCooldown(userId);
        otpService.incrementAttempts(userId);
        otpService.resetWrong(userId);

        emailService.sendOtpEmail(user.getEmail(), user.getUsername(), otp);
    }

    private String rotatePendingToken(Long id) {
        // Delete old key if exists
        String oldToken = redisService.getString("pending:user:" + id);
        if (oldToken != null && !oldToken.isBlank()) {
            redisService.delete("pending:" + oldToken);
        }

        String newToken = UUID.randomUUID().toString();

        redisService.set("pending:" + newToken, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        redisService.set("pending:user:" + id, newToken, PENDING_TTL_MINUTES, TimeUnit.MINUTES);

        return newToken;
    }

    private void writePendingCookie(HttpServletResponse response, String token) {
        ResponseCookie pendingCookie = ResponseCookie.from("pendingToken", token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(600)
                .build();

        response.addHeader("Set-Cookie", pendingCookie.toString());
    }

    private String resolveOrCreatePendingToken(Long id, String pendingToken) {
        // Ưu tiên token đang map sẵn theo user trong Redis
        String mappedToken = redisService.getString("pending:user:" + id);

        if (mappedToken != null && !mappedToken.isBlank()) {
            redisService.set("pending:" + mappedToken, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
            redisService.set("pending:user:" + id, mappedToken, PENDING_TTL_MINUTES, TimeUnit.MINUTES);
            return mappedToken;
        }

        // Fallback token từ client chỉ khi xác thực đúng chủ sở hữu
        String tokenToUse = null;
        if (pendingToken != null && !pendingToken.isBlank()) {
            String ownerId = redisService.getString("pending:" + pendingToken);
            if (ownerId != null && ownerId.equals(id.toString())) {
                tokenToUse = pendingToken;
            }
        }

        if (tokenToUse == null) {
            tokenToUse = UUID.randomUUID().toString();
        }

        redisService.set("pending:" + tokenToUse, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        redisService.set("pending:user:" + id, tokenToUse, PENDING_TTL_MINUTES, TimeUnit.MINUTES);

        return tokenToUse;
    }

    private void clearPendingCookie(HttpServletResponse response) {
        // Xóa cookie pendingToken phía client
        ResponseCookie expiredCookie = ResponseCookie.from("pendingToken", "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", expiredCookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        // Xóa cookie pendingToken phía client
        ResponseCookie expiredCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth/refresh-token")
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", expiredCookie.toString());
    }
}
