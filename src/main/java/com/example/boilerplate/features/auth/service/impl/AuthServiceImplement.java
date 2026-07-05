package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.common.constant.SuccessCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.util.RequestUtils;
import com.example.boilerplate.features.auth.dto.request.LoginRequest;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.dto.request.VerifyOtpRequest;
import com.example.boilerplate.features.auth.dto.response.*;
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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final RequestUtils requestUtils;

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request, HttpServletResponse response, String pendingToken) {

        // Kiểm tra mật khẩu và mật khẩu xác nhận có khớp không
        if (!request.password().trim().equals(request.confirmPassword().trim())) {
            throw new AppException(ErrorCode.PASSWORD_MISMATCH);
        }

        // Email đã được dùng bởi tài khoản đang hoạt động - ACTIVE
        if (userRepository.isEmailAlreadyInUse(request.email().trim().toLowerCase())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_IN_USE);
        }

        // Email thuộc tài khoản bị ban - BANNED
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
        // Thứ tự kiểm tra: attempts → wrong → otp → cooldown

        Long userId = user.getId();

        // Snapshot state 1 lần
        int attempts = otpService.getAttempts(userId);
        int wrong = otpService.getWrong(userId);
        String currentOtp = otpService.getOtp(userId);
        boolean otpAlive = (currentOtp != null && !currentOtp.isBlank());
        long otpTtl = otpService.getOtpTtl(userId);
        long cooldownTtl = otpService.getCooldownTtl(userId);
        Long attemptsTTL = otpService.getAttemptsTtl(userId);

        // ========== Tầng 1: attempts ≥ 5 ? ==========
        if (attempts >= OtpServiceImpl.MAX_ATTEMPTS) {
            // ────── Tầng 2: wrong ≥ 5 ? ──────

            if (wrong >= OtpServiceImpl.MAX_WRONG) {
                // attempts >= 5 + wrong >= 5
                // BLOCK · 1010
                // FE điều hướng về trang verify otp, block user verify otp cho
                // đến khi TTL của top:attempts:{userId} hết hạn
                return RegisterResponse.builder()
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                        .attemptsTTL(Math.max(0, attemptsTTL))
                        .build();
            }

            // ────── Tầng 3: otp còn hạn ? ──────
            if (otpAlive) {

                // ── Tầng 4: cooldown > 0 ? ──
                if (cooldownTtl > 0) {
                    // attempts >= 5 + cooldown > 0 (otp còn hạn)
                    // REUSE · 1001
                    // Sử dụng lại otp cũ, cấp lại pending token, FE điều hướng sang trang verify otp
                    // Hiển thị cooldown, TTL của OTP, số lần nhập sai còn lại của mã otp
                    String token = resolveOrCreatePendingToken(userId, pendingToken);
                    writePendingCookie(response, token);

                    return RegisterResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_COOLDOWN_ACTIVE.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_COOLDOWN_ACTIVE.getMessage())
                            .otpExpiresIn(Math.max(0, otpTtl))
                            .cooldownRemaining(Math.max(0, cooldownTtl))
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                            .build();
                } else {
                    // attempts >= 5 + otp còn hạn
                    // REUSE · 1002
                    // Sử dụng lại otp cũ, cấp lại pending token, FE điều hướng sang trang verify otp
                    // Hiển thị TTL của OTP, số lần nhập sai còn lại của mã otp
                    String token = resolveOrCreatePendingToken(userId, pendingToken);
                    writePendingCookie(response, token);

                    return RegisterResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_NOT_REACHED.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_NOT_REACHED.getMessage())
                            .otpExpiresIn(Math.max(0, otpTtl))
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                            .build();
                }
            }

            // Trường hợp OTP không còn hạn
            else {
                // attempts >= 5, wrong < 5, OTP hết hạn
                // BLOCK · 1011
                // Điều hướng sang trang verify otp, thông báo phải đợi TTL attempts chạy hết
                // mới có thể verify otp tiếp
                return RegisterResponse.builder()
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getCode())
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getMessage())
                        .attemptsTTL(Math.max(0, attemptsTTL))
                        .build();
            }
        }

        // ========== Nhánh attempts < 5 ==========
        // ────── Tầng 2: wrong ≥ 5 ? ──────
        if (wrong >= OtpServiceImpl.MAX_WRONG) {
            // attempts <= 5 + wrong >= 5
            // BLOCK · 1006
            // Không block, thông báo cho user resend otp để tiếp tục verify otp
            // Trường hợp resend otp khi cooldown vẫn còn => phải đợi cooldown hết
            // mới được resend tiếp
            return RegisterResponse.builder()
                    .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                    .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                    .otpExpiresIn(Math.max(0, otpTtl))
                    .cooldownRemaining(Math.max(0, cooldownTtl))
                    .wrongRemaining(0)
                    .build();
        }

        // ────── Tầng 3: otp còn hạn ? ──────

        if (otpAlive) {
            // ── Tầng 4: cooldown > 0 ? ──
            // attempt < 5, otp còn hạn, cooldown active
            // Cấp lại pending token, điều hướng qua trang verify otp,
            // hiển thị cooldown TTL + OTP ttl + số lần nhập còn lại cho mã otp hiện tại
            if (cooldownTtl > 0) {
                // REUSE · 1003
                String token = resolveOrCreatePendingToken(userId, pendingToken);
                writePendingCookie(response, token);

                return RegisterResponse.builder()
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_COOLDOWN_ACTIVE.getCode())
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_COOLDOWN_ACTIVE.getMessage())
                        .otpExpiresIn(Math.max(0, otpTtl))
                        .cooldownRemaining(Math.max(0, cooldownTtl))
                        .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                        .build();
            } else {
                // attempt < 5, otp còn hạn
                // REUSE · 1005
                // Cấp lại pending token, điều hướng qua trang verify otp,
                // hiển thị OTP ttl + số lần nhập còn lại cho mã otp hiện tại
                String token = resolveOrCreatePendingToken(userId, pendingToken);
                writePendingCookie(response, token);

                return RegisterResponse.builder()
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_OTP_NOT_EXPIRED.getCode())
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_OTP_NOT_EXPIRED.getMessage())
                        .otpExpiresIn(Math.max(0, otpTtl))
                        .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                        .build();
            }
        }

        // ────── OTP đã hết hạn → NEW · 1004 ──────
        // Cấp lại token mới
        String token = rotatePendingToken(userId);
        writePendingCookie(response, token);

        String otp = otpService.generateOtp();
        otpService.saveOtp(userId, otp);
        otpService.setCooldown(userId);
        otpService.incrementAttempts(userId);
        otpService.resetWrong(userId);
        emailService.sendOtpEmail(email, username, otp);

        return RegisterResponse.builder()
                .code(SuccessCode.NEW_OTP_CREATED.getCode())
                .message(SuccessCode.NEW_OTP_CREATED.getMessage())
                .otpExpiresIn(OtpServiceImpl.OTP_TTL_SECONDS)
                .cooldownRemaining(OtpServiceImpl.COOLDOWN_SECONDS)
                .wrongRemaining(OtpServiceImpl.MAX_WRONG)
                .build();
    }

    @Override
    @Transactional
    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request, String pendingToken, HttpServletResponse httpServletResponse) {

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

        // ======== Snapshot state một lần để tránh race condition ========
        int attempts = otpService.getAttempts(uid);
        int wrongCount = otpService.getWrong(uid);
        String currentOtp = otpService.getOtp(uid);
        long otpTtl = otpService.getOtpTtl(uid);
        Long attemptsTTL = otpService.getAttemptsTtl(uid);

        // ======== Decision tree: attempts → wrong → otp ========
        // attempts >= 5
        if (attempts >= OtpServiceImpl.MAX_ATTEMPTS) {

            if (wrongCount >= OtpServiceImpl.MAX_WRONG) {
                // attempts>=5 + wrong>=5 → BLOCK · 1010
                // Block verify otp cho đến khi TTL của otp:attempts:{userId} chạy hết
                // FE điều hướng sang trang otp, hiển thị thông báo đợi otp:attempts:{userId}
                // hết hạn rồi quay lại đăng kí tài khoản lại
                return VerifyOtpResponse.builder()
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                        .attemptsTTL(Math.max(0, attemptsTTL))
                        .build();
            }

            else {
                // attempts>=5 + wrong<5 → check otp
                if (currentOtp == null || currentOtp.isBlank()) {
                    // attempts>=5 + wrong<5 + otp hết hạn → BLOCK · 1011
                    // Block verify otp cho đến khi TTL của otp:attempts:{userId} chạy hết
                    // FE điều hướng sang trang otp, hiển thị thông báo đợi otp:attempts:{userId}
                    // hết hạn rồi quay lại đăng kí tài khoản lại
                    return VerifyOtpResponse.builder()
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getMessage())
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getCode())
                            .attemptsTTL(Math.max(0, attemptsTTL))
                            .build();
                }
                // attempts>=5 + wrong<5 + otp còn hạn → fall-through xuống SO KHỚP OTP
            }
        } else {
            // attempts < 5
            if (wrongCount >= OtpServiceImpl.MAX_WRONG) {
                // attempts<5 + wrong>=5 → BLOCK · 1006
                // FE điều hướng sang trang verify otp, user chỉ cần click resend otp
                // để được cấp OTP mới
                return VerifyOtpResponse.builder()
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                        .wrongRemaining(0)
                        .build();
            } else {
                // attempts<5 + wrong<5 → check otp
                if (currentOtp == null || currentOtp.isBlank()) {
                    // attempts<5 + wrong<5 + otp hết hạn → BLOCK · 1007
                    // FE điều hướng sang trang verify otp, user chỉ cần click resend otp
                    // để được cấp OTP mới
                    return VerifyOtpResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED.getMessage())
                            .otpExpiresIn(0L)
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrongCount))
                            .build();
                }
                // attempts<5 + wrong<5 + otp còn hạn → fall-through xuống SO KHỚP
            }
        }

        // ======== SO KHỚP otp ========
        String otp = currentOtp; // dùng lại snapshot

        // Nếu OTP hết hạn, trường hợp này
        // Đây là deadcode, vì trường hợp hết hạn đã được xử lí ở trên, 
        // nhưng vẫn giữ lại để phòng hờ
        if (otp == null || otp.isBlank()) {
            return VerifyOtpResponse.builder()
                .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED.getCode())
                .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_OTP_EXPIRED.getMessage())
                .otpExpiresIn(0L)
                .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrongCount))
                .build();
        }

        // Nếu otp từ request không đúng thì tăng wrong counter
        // và trả lỗi OTP invalid
        if (!otp.equals(request.otp().trim())) {
            otpService.incrementWrong(uid);
            int remaining = Math.max(0, OtpServiceImpl.MAX_WRONG - otpService.getWrong(uid));

            return VerifyOtpResponse.builder()
                    .code(SuccessCode.OTP_NOT_MATCH.getCode())
                    .message(SuccessCode.OTP_NOT_MATCH.getMessage())
                    .otpExpiresIn(Math.max(0, otpTtl))
                    .wrongRemaining(remaining)
                    .build();
        }

        // Nếu otp đúng thì đổi trạng thái của user sang activate = true
        User user = userRepository.findByIdWithRoles(uid).orElseThrow(
                () -> new AppException(ErrorCode.USER_NOT_FOUND)
        );

        // Gán ROLE_USER mặc định khi verify thành công (nếu user chưa có role này)
        boolean hasUserRole = user.getRoles() != null &&
                user.getRoles().stream().anyMatch(role -> role.getName() == RoleEnum.USER);

        if (!hasUserRole) {
            var defaultRole = roleRepository.findByName(RoleEnum.USER)
                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_ERROR));
            user.getRoles().add(defaultRole);
        }

        user.setActive(true);
        userRepository.save(user);

        // Dọn state otp sau khi verify thành công
        otpService.clearAll(user.getId());
        
        // Phòng hờ: xóa thẳng pending token hiện tại + reverse index của user,
        // đề phòng reverse index bị lệch (stale) khiến clearAll ở trên dọn không sạch.
        redisService.delete("pending:" + pendingToken);
        redisService.delete("pending:user:" + uid);
        
        // Xóa pending cookie ở client
        clearPendingCookie(httpServletResponse);

        // Gửi mail chào mừng quý vị đã đến website của chúng tôi
        emailService.sendWelcomeEmail(user.getEmail(), user.getUsername());

        return VerifyOtpResponse.builder()
                .code(SuccessCode.VERIFY_OTP_SUCCESS.getCode())
                .message(SuccessCode.VERIFY_OTP_SUCCESS.getMessage())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ResendOtpResponse resendOtp(String pendingToken, HttpServletResponse httpServletResponse) {
        // Nếu pendingToken từ request không có => Hết phiên OTP
        if (pendingToken == null || pendingToken.isBlank()) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        Long uid;

        // Lấy userId của pendingToken hiện tại
        try {
            String userId = redisService.getString("pending:" + pendingToken);

            // Nếu user id null thì có nghĩa phiên xác minh otp đã hết hạn
            // xóa luôn pendingCookie
            if (userId == null || userId.isBlank()) {
                clearPendingCookie(httpServletResponse);
                throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
            }

            // chuyển userId từ String sang Long, nếu có
            // lỗi trong quá trình ép kiểu => phiên xác minh hết hạn
            // xóa pendingCookie. ko xo các key redis khác vì chưa xác minh
            // được userId chính xác
            uid = Long.parseLong(userId);

        } catch (NumberFormatException e) {
            clearPendingCookie(httpServletResponse);
            throw new AppException(ErrorCode.OTP_VERIFICATION_SESSION_EXPIRED);
        }

        // ======== Snapshot state một lần để tránh race condition ========
        int attempts = otpService.getAttempts(uid);
        int wrong = otpService.getWrong(uid);
        String currentOtp = otpService.getOtp(uid);
        boolean otpAlive = (currentOtp != null && !currentOtp.isBlank());
        long otpTtl = otpService.getOtpTtl(uid);
        long cooldownTtl = otpService.getCooldownTtl(uid);
        Long attemptsTTL = otpService.getAttemptsTtl(uid);

        // ======== Decision tree: cooldown → attempts → otp → wrong ========
        // ────── Tầng 1: cooldown > 0 ? ──────
        if (cooldownTtl > 0) {
            // cooldown > 0
            // BLOCK · 1012
            // Chặn resend otp, hiển thị thời gian cooldown còn lại
            return ResendOtpResponse.builder()
                    .code(SuccessCode.COOLDOWN_ACTIVE.getCode())
                    .message(SuccessCode.COOLDOWN_ACTIVE.getMessage())
                    .otpExpiresIn(Math.max(0, otpTtl))
                    .cooldownRemaining(Math.max(0, cooldownTtl))
                    .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                    .build();
        }

        // ────── Tầng 2: attempts ≥ 5 ? ──────
        if (attempts >= OtpServiceImpl.MAX_ATTEMPTS) {
            // ────── Tầng 3: otp còn hạn ? ──────
            if (otpAlive) {
                // ── Tầng 4: wrong ≥ 5 ? ──
                if (wrong >= OtpServiceImpl.MAX_WRONG) {
                    // attempts >= 5 + otp còn hạn + wrong >= 5
                    // BLOCK · 1010
                    // Chặn resend otp, hiển thị thời gian TTL của attempts còn lại
                    return ResendOtpResponse.builder()
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                            .attemptsTTL(Math.max(0, attemptsTTL))
                            .build();
                } else {
                    // attempts >= 5 + otp còn hạn + wrong < 5
                    // BLOCK · 1008
                    // Chặn resend otp, hiển thị thời gian TTL của attempts còn lại,
                    // thời gian OTP còn lại, số lần nhập sai còn lại
                    return ResendOtpResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED.getMessage())
                            .attemptsTTL(Math.max(0, attemptsTTL))
                            .otpExpiresIn(Math.max(0, otpTtl))
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                            .build();
                }
            } else {
                // attempts >= 5 + otp hết hạn
                // BLOCK · 1011
                // Chặn resend otp, hiển thị thời gian TTL của attempts còn lại
                return ResendOtpResponse.builder()
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getMessage())
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getCode())
                        .attemptsTTL(Math.max(0, attemptsTTL))
                        .build();
            }
        }

        // ────── attempts < 5 → NEW · 1009 (escape hatch: không xét otp/wrong) ──────
        // Tạo và gửi OTP mới
        User user = userRepository.findById(uid).orElseThrow(
                () -> new AppException(ErrorCode.USER_NOT_FOUND)
        );

        // Tạo mã otp mới
        String otp = otpService.generateOtp();

        // Reset TTL của otp:{userId} với TTL 5 phút
        otpService.saveOtp(uid, otp);
        // Set cooldown cho otp hiện tại về 60s
        otpService.setCooldown(uid);
        // Reset wrong về 0
        otpService.resetWrong(uid);
        // Tăng số lần resend otp thêm 1
        otpService.incrementAttempts(uid);

        // Sau khi có otp mới thì rotate pendingToken để đồng bộ Redis + cookie
        String newPendingToken = rotatePendingToken(uid);
        writePendingCookie(httpServletResponse, newPendingToken);

        // Gửi email cho user
        emailService.sendOtpEmail(user.getEmail(), user.getUsername(), otp);

        return ResendOtpResponse.builder()
                .code(SuccessCode.RESEND_OTP_SUCCESS.getCode())
                .message(SuccessCode.RESEND_OTP_SUCCESS.getMessage())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResult login(LoginRequest loginRequest, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, String pendingToken) {

        // Chuẩn hóa email và password
        String email = loginRequest.email().trim().toLowerCase();
        String password = loginRequest.password().trim();

        // Tạo UsernamePasswordAuthenticationToken chứa thông tin cần xác thực
        UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                new UsernamePasswordAuthenticationToken(email, password);

        Authentication authenticatedToken;

        // xác thực, lúc này loadUserByUsername được gọi
        try {
            // Xác thực và trả về kết quả là 1 đối tượng Authentication
            authenticatedToken = authenticationManager.authenticate(usernamePasswordAuthenticationToken);
        }

        catch (BadCredentialsException e) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        } catch (AuthenticationException e) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        // Không bắt LockedException và DisabledException trong khi
        // xác thực (đọc lí do ở UserDetailServiceImpl), chỉ kiểm
        // tra tài khoản bị lock hay disable sau khi đã xác thực

        // Password đã đúng -> giờ mới check trạng thái nghiệp vụ
        CustomUserDetails userDetails = (CustomUserDetails) authenticatedToken.getPrincipal();

        // Nếu tài khoản bị xóa
        if (userDetails.isDeleted()) {
            throw new AppException(ErrorCode.ACCOUNT_BANNED);
        }

        // Tài khoản chưa active: trả về LoginInactiveResponse (HTTP 200)
        // mang đầy đủ thông tin OTP (otpExpiresIn/cooldownRemaining/wrongRemaining + code)
        // để FE chuyển sang trang verify OTP. Các nhánh dead-end thật vẫn throw bên trong.
        if (!userDetails.isActive()) {
            return handleInactiveUserLogin(userDetails, httpServletResponse, pendingToken);
        }

        // Tạo sessionId đại diện cho phiên đăng nhập của user hiện tại
        String sessionId = UUID.randomUUID().toString();

        // Tạo accessToken và refreshToken dựa trên thông tin của User
        // trong đó có jti (UUID) và sessionId (UUID) và deviceId (UUID)
        String accessToken = jwtUtil.generateAccessToken(userDetails, sessionId, loginRequest.deviceId().toString());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails, sessionId, loginRequest.deviceId().toString());

        // Lưu session vào redis
        redisService.createSession(
                sessionId,
                userDetails.getUsername(),
                loginRequest.deviceId().toString(),
                jwtUtil.extractJti(refreshToken),
                loginRequest.deviceName(),
                requestUtils.getClientIp(httpServletRequest),
                requestUtils.getUserAgent(httpServletRequest)
        );

        // Lưu sessionId vào danh sách session của user hiện tại
        redisService.addSessionToUser(userDetails.getId().toString(), sessionId);

        // Thêm refreshToken vào cookie HttpOnly
        writeCookie(
                httpServletResponse,
                "refreshToken",
                refreshToken,
                true,
                true,
                "/",
                "Strict",
                7 * 24 * 60 * 60L
        );

        // trả về thông tin của user (jti, username, roles, access token)
        return new AuthResponse(
                SuccessCode.LOGIN_SUCCESS.getCode(),
                userDetails.getId(),
                userDetails.getUsername(),
                userDetails.getRoles(),
                accessToken
        );
    }

    @Override
    public AuthResponse refreshToken(String refreshToken, HttpServletResponse httpServletResponse) {
        // Nếu không có refreshtoken hay refreshtoken trống
        // thì ghì đè cookie refreshtoken để xóa
        if (refreshToken == null || refreshToken.isBlank()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String usernameFromToken;
        String jtiFromToken;
        String sessionIdFromToken;
        String deviceIdFromToken;

        try {
            usernameFromToken = jwtUtil.extractUsername(refreshToken);
            jtiFromToken = jwtUtil.extractJti(refreshToken);
            sessionIdFromToken = jwtUtil.extractSessionId(refreshToken);
            deviceIdFromToken = jwtUtil.extractDeviceId(refreshToken);
        } catch (Exception e) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // kiểm tra RT đã bị thu hồi hay chưa
        if (tokenBlacklistService.isRefreshTokenRevoked(jtiFromToken)) {
            throw new AppException(ErrorCode.TOKEN_REVOKED);
        }

        // kiểm tra session có tồn tại không
        String sessionKey = RedisService.SESSION_KEY_PREFIX + sessionIdFromToken;
        if (!redisService.hasKey(sessionKey)) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.SESSION_INACTIVE);
        }

        // Kiểm tra status trong session:{sessionId} có còn active không
        Object statusObj = redisService.getSessionField(sessionIdFromToken, "status");
        if (statusObj == null || !"ACTIVE".equals(statusObj.toString())) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.SESSION_INACTIVE);
        }

        // Kiểm tra jti của token và jtiRefreshToken trong session có giống nhau không
        Object currentRefreshJtiObj = redisService.getSessionField(sessionIdFromToken, "refreshJtiCurrent");
        if (currentRefreshJtiObj == null) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.SESSION_INACTIVE);
        }

        String jtiRefreshTokenFromSession = currentRefreshJtiObj.toString();
        if (!jtiRefreshTokenFromSession.equals(jtiFromToken)) {
            redisService.updateSessionField(sessionIdFromToken, "status", "REVOKED");
            clearRefreshTokenCookie(httpServletResponse);
            tokenBlacklistService.revokeRefreshToken(jtiFromToken, jwtUtil.remainingTimeOf(refreshToken));
            throw new AppException(ErrorCode.TOKEN_REUSE_DETECTED);
        }

        User user = userRepository.findByUsernameWithRoles(usernameFromToken)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        // Nếu user đã bị ban thì xóa refreshtoken
        if (user.isDeleted()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.ACCOUNT_BANNED);
        }

        // Nếu user inactive thì xóa refreshtoken
        if (!user.isActive()) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.USER_INACTIVE);
        }

        CustomUserDetails userDetails = new CustomUserDetails(user);

        // Nếu token ko hợp lệ -> xóa refreshtoken
        if (!jwtUtil.isTokenValid(refreshToken, userDetails)) {
            clearRefreshTokenCookie(httpServletResponse);
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        // Cấp access token mới
        String newAccessToken = jwtUtil.generateAccessToken(userDetails, sessionIdFromToken, deviceIdFromToken);

        // Rotate refresh token để đồng bộ TTL Redis với exp bên trong JWT
        String newRefreshToken = jwtUtil.generateRefreshToken(userDetails, sessionIdFromToken, deviceIdFromToken);

        // CỐ Ý KHÔNG blacklist refresh token cũ ở đây.
        // Chống reuse đã dựa vào refreshJtiCurrent rồi: RT cũ có jti != refreshJtiCurrent, nên nếu bị
        // replay sẽ rơi đúng vào nhánh reuse ở trên (set status = REVOKED + ném 3013) → giết cả phiên.
        // Nếu blacklist RT cũ ở đây, guard TOKEN_REVOKED (2010) chạy trước sẽ chặn mất, không bao giờ
        // tới được nhánh reuse → replay token bị lộ chỉ nhận 2010 mà session vẫn ACTIVE (mất tính năng
        // "phát hiện lộ token ⇒ vô hiệu hóa cả family"). RT cũ vẫn "chết" nhờ jti không còn khớp.

        // update session:{sessionId}.refreshJtiCurrent = jti của refreshToken mới được cấp
        String newRefreshJti = jwtUtil.extractJti(newRefreshToken);
        redisService.updateSessionField(sessionIdFromToken, "refreshJtiCurrent", newRefreshJti);

        // Update lastseen  trong session:{sessionId}
        redisService.updateSessionField(sessionIdFromToken, "lastSeen", LocalDateTime.now().toString());

        // ghi refreshToken vào cookie
        writeCookie(
                httpServletResponse,
                "refreshToken",
                newRefreshToken,
                true,
                true,
                "/",
                "Strict",
                7 * 24 * 60 * 60L
        );

        return new AuthResponse(
                SuccessCode.LOGIN_SUCCESS.getCode(),
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
     * Access tokens are stateless JWTs; we do not keep a blacklist here.
     */
    @Override
    public void logout(String refreshToken, HttpServletRequest request, HttpServletResponse response) {

        // Luôn luôn xóa cookie cho dù cookie mất hoặc không hợp lệ
        clearRefreshTokenCookie(response);

        // dòng này thấy hơi thừa =))
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        // Lấy header Authorization từ request
        String authorizationHeader = request.getHeader("Authorization");

        // Lấy acccesstoken từ Authorization Bearer, nếu không có thì bỏ qua
        String accessToken = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            accessToken = authorizationHeader.substring(7);
        }

        String username;
        long remainingTTLRefreshToken;
        String sessionIdFromToken;

        try {
            remainingTTLRefreshToken = jwtUtil.remainingTimeOf(refreshToken);
            username = jwtUtil.extractUsername(refreshToken);
            sessionIdFromToken = jwtUtil.extractSessionId(refreshToken);
        } catch (Exception e) {
            return;
        }

        // Kiểm tra session:{sessionId} có tồn tại không
        String sessionKey = RedisService.SESSION_KEY_PREFIX + sessionIdFromToken;
        if (!redisService.hasKey(sessionKey)) {
            return;
        }

        Object currentRefreshJtiObj = redisService.getSessionField(sessionIdFromToken, "refreshJtiCurrent");
        String currentRefreshJti = currentRefreshJtiObj != null ? currentRefreshJtiObj.toString() : null;

        String jtiAccessToken = null;
        Long remainingAccessToken = null;

        if (accessToken != null) {
            try {
                jtiAccessToken = jwtUtil.extractJti(accessToken);
                remainingAccessToken = jwtUtil.remainingTimeOf(accessToken);
            } catch (Exception ignored) {
                // access token hỏng/hết hạn thì bỏ qua, vẫn tiếp tục logout
            }
        }

        // Xóa session:{sessionId}
        redisService.deleteSession(sessionIdFromToken);

        // xóa sessionId khỏi user:sessions:{userId} — best-effort: nếu không tra ra
        // user thì bỏ qua bước này, KHÔNG throw, để logout vẫn tiếp tục blacklist token.
        userRepository.findByUsername(username).ifPresent(user ->
                redisService.removeSessionFromUser(user.getId().toString(), sessionIdFromToken));

        // lưu Access Token và Refresh Token vào blacklist
        if (jtiAccessToken != null && remainingAccessToken != null && remainingAccessToken > 0) {
            tokenBlacklistService.revokeAccessToken(jtiAccessToken, remainingAccessToken);
        }

        if (currentRefreshJti != null && remainingTTLRefreshToken > 0) {
            tokenBlacklistService.revokeRefreshToken(currentRefreshJti, remainingTTLRefreshToken);
        }
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

    // Xử lí trường hợp người dùng đăng nhập bằng tài khoản chưa được
    // xác minh otp
    private LoginInactiveResponse handleInactiveUserLogin(CustomUserDetails userDetails, HttpServletResponse httpServletResponse, String pendingToken) {

        Long userId = userDetails.getId();

        // ======== Snapshot state một lần để tránh race condition ========
        int attempts = otpService.getAttempts(userId);
        int wrong = otpService.getWrong(userId);
        String currentOtp = otpService.getOtp(userId);
        boolean otpAlive = (currentOtp != null && !currentOtp.isBlank());
        long otpTtl = otpService.getOtpTtl(userId);
        long cooldownTtl = otpService.getCooldownTtl(userId);
        Long attemptsTTL = otpService.getAttemptsTtl(userId);

        // ======== Decision tree: attempts → wrong → otp → cooldown ========
        // ────── Tầng 1: attempts ≥ 5 ? ──────
        if (attempts >= OtpServiceImpl.MAX_ATTEMPTS) {
            // ────── Tầng 2: wrong ≥ 5 ? ──────
            if (wrong >= OtpServiceImpl.MAX_WRONG) {
                // attempts >= 5 + wrong >= 5
                // BLOCK · 1010
                // Block verify otp cho đến khi TTL của otp:attempts:{userId} chạy hết
                return LoginInactiveResponse.builder()
                        .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                        .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                        .attemptsTTL(Math.max(0, attemptsTTL))
                        .build();
            }

            // ────── Tầng 3: otp còn hạn ? ──────
            if (otpAlive) {
                // ── Tầng 4: cooldown > 0 ? ──
                if (cooldownTtl > 0) {
                    // attempts >= 5 + wrong < 5 + otp còn hạn + cooldown > 0
                    // REUSE · 4004
                    String token = resolveOrCreatePendingToken(userId, pendingToken);
                    writePendingCookie(httpServletResponse, token);

                    return LoginInactiveResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_COOLDOWN_ACTIVE.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_COOLDOWN_ACTIVE.getMessage())
                            .otpExpiresIn(Math.max(0, otpTtl))
                            .cooldownRemaining(Math.max(0, cooldownTtl))
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                            .build();
                } else {
                    // attempts >= 5 + wrong < 5 + otp còn hạn + cooldown = 0
                    // REUSE · 1008
                    String token = resolveOrCreatePendingToken(userId, pendingToken);
                    writePendingCookie(httpServletResponse, token);

                    return LoginInactiveResponse.builder()
                            .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED.getCode())
                            .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_NOT_EXPIRED_AND_WRONG_LIMIT_NOT_REACHED.getMessage())
                            .otpExpiresIn(Math.max(0, otpTtl))
                            .cooldownRemaining(0L)
                            .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                            .build();
                }
            }

            // attempts >= 5 + wrong < 5 + otp hết hạn
            // BLOCK · 1011
            return LoginInactiveResponse.builder()
                    .code(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getCode())
                    .message(SuccessCode.OTP_ATTEMPTS_LIMIT_REACHED_AND_OTP_EXPIRED.getMessage())
                    .attemptsTTL(Math.max(0, attemptsTTL))
                    .build();
        }

        // ────── attempts < 5 ──────
        // ────── Tầng 2: wrong ≥ 5 ? ──────
        if (wrong >= OtpServiceImpl.MAX_WRONG) {
            // attempts < 5 + wrong >= 5
            // BLOCK · 1006
            return LoginInactiveResponse.builder()
                    .code(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getCode())
                    .message(SuccessCode.OTP_ATTEMPTS_LIMIT_NOT_REACHED_AND_WRONG_LIMIT_REACHED.getMessage())
                    .otpExpiresIn(Math.max(0, otpTtl))
                    .cooldownRemaining(Math.max(0, cooldownTtl))
                    .wrongRemaining(0)
                    .build();
        }

        // ────── Tầng 3: otp còn hạn ? ──────
        if (otpAlive) {
            // ── Tầng 4: cooldown > 0 ? ──
            if (cooldownTtl > 0) {
                // attempts < 5 + wrong < 5 + otp còn hạn + cooldown > 0
                // REUSE · 4003
                String token = resolveOrCreatePendingToken(userId, pendingToken);
                writePendingCookie(httpServletResponse, token);

                return LoginInactiveResponse.builder()
                        .code(SuccessCode.LOGIN_INACTIVE_OTP_REUSED.getCode())
                        .message(SuccessCode.LOGIN_INACTIVE_OTP_REUSED.getMessage())
                        .otpExpiresIn(Math.max(0, otpTtl))
                        .cooldownRemaining(Math.max(0, cooldownTtl))
                        .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                        .build();
            } else {
                // attempts < 5 + wrong < 5 + otp còn hạn + cooldown = 0
                // REUSE · 4005 ★ (mã mới)
                String token = resolveOrCreatePendingToken(userId, pendingToken);
                writePendingCookie(httpServletResponse, token);

                return LoginInactiveResponse.builder()
                        .code(SuccessCode.LOGIN_INACTIVE_OTP_REUSED_NO_COOLDOWN.getCode())
                        .message(SuccessCode.LOGIN_INACTIVE_OTP_REUSED_NO_COOLDOWN.getMessage())
                        .otpExpiresIn(Math.max(0, otpTtl))
                        .cooldownRemaining(0L)
                        .wrongRemaining(Math.max(0, OtpServiceImpl.MAX_WRONG - wrong))
                        .build();
            }
        }

        // ────── attempts < 5 + wrong < 5 + otp hết hạn → NEW · 4002 ──────
        String token = rotatePendingToken(userId);
        writePendingCookie(httpServletResponse, token);

        String otp = otpService.generateOtp();
        otpService.saveOtp(userId, otp);
        otpService.setCooldown(userId);
        otpService.incrementAttempts(userId);
        otpService.resetWrong(userId);

        emailService.sendOtpEmail(userDetails.getEmail(), userDetails.getUsername(), otp);

        return LoginInactiveResponse.builder()
                .code(SuccessCode.LOGIN_INACTIVE_OTP_SENT.getCode())
                .message(SuccessCode.LOGIN_INACTIVE_OTP_SENT.getMessage())
                .otpExpiresIn(OtpServiceImpl.OTP_TTL_SECONDS)
                .cooldownRemaining(OtpServiceImpl.COOLDOWN_SECONDS)
                .wrongRemaining(OtpServiceImpl.MAX_WRONG)
                .build();
    }

    private String rotatePendingToken(Long id) {
        // xóa pending token cũ
        String oldToken = redisService.getString("pending:user:" + id);
        if (oldToken != null && !oldToken.isBlank()) {
            redisService.delete("pending:" + oldToken);
        }

        // tạo token mới
        String newToken = UUID.randomUUID().toString();

        // lưu pending token mới và reverse pending token mới
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
        String pendingTokenFromRedis = redisService.getString("pending:user:" + id);

        if (pendingTokenFromRedis != null && !pendingTokenFromRedis.isBlank()) {
            // Gia hạn thời gian sống TTL cho pendingToken và reverse pending token
            redisService.set("pending:" + pendingTokenFromRedis, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
            redisService.set("pending:user:" + id, pendingTokenFromRedis, PENDING_TTL_MINUTES, TimeUnit.MINUTES);
            return pendingTokenFromRedis;
        }

        // Nếu không tìm thấy pending token từ redis
        // Fallback token từ client chỉ khi xác thực đúng chủ sở hữu
        String tokenToUse = null;
        if (pendingToken != null && !pendingToken.isBlank()) {
            // Lấy userId của token
            String ownerId = redisService.getString("pending:" + pendingToken);

            // kiểm userId của token có phải là id đang đăng kí ko
            if (ownerId != null && ownerId.equals(id.toString())) {
                tokenToUse = pendingToken;
            }
        }

        // Nếu token null thì tạo mới 1 cái khác
        if (tokenToUse == null) {
            tokenToUse = UUID.randomUUID().toString();
        }

        // gia hạn lại thời gian cho pending token và reverse pending token
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
                .path("/")
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", expiredCookie.toString());
    }
}
