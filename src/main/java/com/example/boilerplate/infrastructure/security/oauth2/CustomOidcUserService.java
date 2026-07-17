package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.constant.AuthProvider;
import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.common.exception.AccountBannedException;
import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.features.user.entity.Role;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.RoleRepository;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // 1. super.loadUser() lấy OidcUser từ Google (ID token + UserInfo)
        // KHÔNG @Transactional ở đây: super.loadUser() gọi HTTP tới Google,
        //   giữ DB connection suốt network I/O là anti-pattern.
        //   Phần DB (find/create/link user) sẽ được thực hiện riêng.
        OidcUser oidcUser = super.loadUser(userRequest);
        Map<String,Object> claims = oidcUser.getClaims();

        // 2. Lấy claims - check null/blank trước khi chuẩn hóa
        String rawEmail = (String) claims.get("email");
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_provided",
                            "Email not provided by Google", null));
        }

        String email = rawEmail.toLowerCase().trim();

        String name = (String) claims.get("name");
        String sub = (String) claims.get("sub");
        String picture = (String) claims.get("picture");
        Boolean emailVerified = (Boolean) claims.get("email_verified");

        log.info("OIDC login attempt: email={}, provider=GOOGLE, sub={}", email, sub);

        // 3. Kiểm tra email_verified trước khi link
        // Thiếu check này = lỗ hổng account-takeover: attacker có thể tạo Google account
        // với email chưa verify → link vào victim account.
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_not_verified",
                            "Email not verified by Google. Please verify your email and try again.",
                            null));
        }

        // 4. Tìm user — email đã normalize (lowercase + trim) khớp với local flow
        User user = userRepository.findByEmailWithRoles(email).orElse(null);

        if (user == null) {
            // ===== USER MỚI: Tạo tài khoản mới =====
            user = new User();
            user.setEmail(email);
            user.setUsername(email);
            user.setFullName(name);
            user.setAvatarUrl(picture);
            // 🔴 CRITICAL FIX: DB có NOT NULL constraint cho password
            // → Không thể setPassword(null) hay ""
            // → Hash UUID ngẫu nhiên: không ai login password được, an toàn
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setActive(true);                  // Google đã verify → auto active
            user.setDeleted(false);
            user.setAuthProvider(AuthProvider.GOOGLE);
            user.setSocialId(sub);
            user.setRoles(new HashSet<>());

            // Role USER — throw OAuth2AuthenticationException nếu role không tồn tại
            // (không dùng RuntimeException → sẽ thành 500 thô)
            Role userRole = roleRepository.findByName(RoleEnum.USER)
                    .orElseThrow(() -> new OAuth2AuthenticationException(
                            new OAuth2Error(
                                    "internal_error",
                                    "Default role not found",
                                    null
                            )));

            user.getRoles().add(userRole);

            userRepository.save(user);

            log.info("Created new user from OIDC: email={}", email);
        } else {
            // ===== USER ĐÃ TỒN TẠI: Link Google account =====

            // STEP 5: Check BANNED
            if (user.isDeleted()) {
                log.warn("BANNED user tried OIDC login: email={}", email);
                throw new AccountBannedException(
                        "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ hỗ trợ.");
            }

            boolean mutated = false;

            // STEP 6: Auto-activate nếu inactive (chưa verify OTP)
            if (!user.isActive()) {
                user.setActive(true);
                mutated = true;
                // Dọn Redis state OTP — tránh sót key otp:*, pending:* tới hết TTL
                otpService.clearAll(user.getId());
                log.info("Activated inactive user via OIDC + cleared OTP state: email={}", email);
            }

            // STEP 7: Link Google — KHÔNG ghi đè authProvider (giữ LOCAL)
            if (user.getSocialId() == null || user.getSocialId().isBlank()) {
                user.setSocialId(sub);
                mutated = true;
            }

            if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
                user.setAvatarUrl(picture);
                mutated = true;
            }

            // Chỉ save khi có mutation — tránh touch updated_at mỗi lần login
            if (mutated) {
                userRepository.save(user);
                log.info("Updated user via OIDC: email={}, mutations applied", email);
            }
        }

        // STEP 8: Tạo CustomOidcUser chứa userId — dùng SimpleGrantedAuthority
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName().getAuthority()))
                .toList();

        return new CustomOidcUser(
                authorities,
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                user.getId(),
                user.getEmail()
        );
    }

}
