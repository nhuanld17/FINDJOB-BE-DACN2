package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.constant.AccountType;
import com.example.boilerplate.common.constant.AuthProvider;
import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.common.exception.AccountBannedException;
import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.features.company.service.CompanyService;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import com.example.boilerplate.features.user.entity.Role;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.RoleRepository;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;



/**
 * <h2>CustomOidcUserService — Xử lý user sau khi Google xác thực OIDC</h2>
 *
 * <h3>Vai trò:</h3>
 * Đây là class <b>quan trọng nhất</b> trong flow OIDC.
 * Sau khi Google xác thực user thành công và trả về ID token,
 * Spring Security gọi {@link #loadUser(OidcUserRequest)} để:
 * <ol>
 *   <li>Gọi Google UserInfo endpoint lấy thông tin user (email, name, picture, sub…)</li>
 *   <li><b>Tìm user trong DB local</b> theo email</li>
 *   <li>
 *      <b>Phân nhánh:</b>
 *      <ul>
 *        <li>Chưa có → <b>Tạo mới</b> user + Employee profile</li>
 *        <li>Đã có → <b>Link Google account</b> + kích hoạt nếu đang inactive</li>
 *      </ul>
 *   </li>
 *   <li>Trả về {@link CustomOidcUser} chứa {@code userId} local để {@link OidcLoginSuccessHandler} tạo JWT</li>
 * </ol>
 *
 * <h3>Vấn đề class này giải quyết:</h3>
 * <ul>
 *   <li><b>Auto-register:</b> User đăng nhập Google lần đầu → tự tạo tài khoản (không cần form đăng ký)</li>
 *   <li><b>Link tài khoản:</b> User đã đăng ký bằng email/password trước đó → đăng nhập Google bằng cùng email
 *       → link Google account vào tài khoản hiện tại (thêm {@code socialId})</li>
 *   <li><b>Auto-activate:</b> User đã register bằng email/password nhưng chưa verify OTP
 *       → Google đã verify email → auto active user</li>
 *   <li><b>Xử lý pending intent:</b> User đăng ký EMPLOYER form bằng email/password, chưa xong OTP
 *       → Google login xong → tạo Company cho user luôn (không cần verify OTP lại)</li>
 *   <li><b>Tách biệt network DB:</b> Gọi {@code super.loadUser()} (HTTP đến Google) → xong mới chạy DB
 *       (không giữ connection pool chờ network)</li>
 * </ul>
 *
 * <h3>Luồng chi tiết (loadUser):</h3>
 * <pre>
 * 1. super.loadUser() → Gọi Google UserInfo, lấy claims
 * 2. Kiểm tra email_verified = true (thiếu check này = lỗ hổng account-takeover)
 * 3. Tìm user trong DB theo email (lowercase + trim khớp với local flow)
 *    │
 *    ├── [KHÔNG TÌM THẤY] → User mới
 *    │     ├── Tạo User với:
 *    │     │   ├── password = NULL (user Google chưa có mật khẩu, đặt lần đầu qua change-password)
 *    │     │   ├── active = true (Google đã verify)
 *    │     │   ├── authProvider = GOOGLE
 *    │     │   └── roles = [USER]
 *    │     ├── Tạo Employee profile
 *    │     └── → Trả về CustomOidcUser
 *    │
 *    └── [TÌM THẤY] → User đã tồn tại
 *          ├── Check BANNED → throw AccountBannedException
 *          ├── Nếu inactive (chưa verify OTP):
 *          │     ├── setActive(true)
 *          │     ├── Xử lý pendingAccountType (COMPANY/USER)
 *          │     │     ├── EMPLOYER → tạo Company
 *          │     │     └── USER → tạo Employee nếu chưa có
 *          │     ├── clear OTP state trong Redis
 *          │     └── log "Activated inactive user via OIDC"
 *          ├── Link Google (socialId, avatar nếu thiếu)
 *          └── → Trả về CustomOidcUser
 * </pre>
 *
 * <h3>Lưu ý bảo mật:</h3>
 * <ul>
 *   <li>Check {@code email_verified} trước — nếu không, attacker tạo Gmail chưa verify
 *       → login được vào victim account (account-takeover)</li>
 *   <li>KHÔNG ghi đè {@code authProvider = LOCAL} — nếu user đã đăng ký bằng password trước,
 *       vẫn giữ LOCAL để không mất khả năng login bằng password</li>
 *   <li>password = NULL — user Google chưa có mật khẩu, đặt lần đầu qua change-password
 *       (không ai login bằng email/password được cho đến khi đặt)</li>
 * </ul>
 *
 * @see OidcUserService
 * @see CustomOidcUser
 * @see OidcLoginSuccessHandler
 * @see com.example.boilerplate.features.user.entity.User
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OtpService otpService;
    private final CompanyService companyService;
    private final EmployeeRepository employeeRepository;

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
            user.setUsername(generateUniqueUsername(email));
            user.setFullName(name);
            user.setAvatarUrl(picture);
            // Set password null cho người dùng đăng kí bằng google
            user.setPassword(null);
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

            // Tạo Employee profile cho user mới (quyền USER)
            Employee employee = new Employee();
            employee.setUser(user);
            employeeRepository.save(employee);

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

                // Xử lý intent đăng ký còn treo — giống verifyOtp
                AccountType pending = user.getPendingAccountType() != null
                        ? user.getPendingAccountType() : AccountType.USER;
                RoleEnum targetRole = (pending == AccountType.EMPLOYER)
                        ? RoleEnum.COMPANY : RoleEnum.USER;

                boolean hasTargetRole = user.getRoles().stream()
                        .anyMatch(r -> r.getName() == targetRole);
                if (!hasTargetRole) {
                    Role role = roleRepository.findByName(targetRole)
                            .orElseThrow(() -> new OAuth2AuthenticationException(
                                    new OAuth2Error("internal_error", "Role not found", null)));
                    user.getRoles().add(role);
                }

                String pendingCompanyName = user.getPendingCompanyName();
                user.setPendingAccountType(null);
                user.setPendingCompanyName(null);

                if (pending == AccountType.EMPLOYER) {
                    String companyName = (pendingCompanyName != null && !pendingCompanyName.isBlank())
                            ? pendingCompanyName : user.getUsername();
                    companyService.createCompanyForOwner(user, companyName);
                }

                // USER: đảm bảo có Employee profile
                if (pending == AccountType.USER) {
                    if (employeeRepository.findByUserId(user.getId()).isEmpty()) {
                        Employee employee = new Employee();
                        employee.setUser(user);
                        employeeRepository.save(employee);
                    }
                }

                // Dọn Redis state OTP — tránh sót key otp:*, pending:* tới hết TTL
                otpService.clearAll(user.getId());
                log.info("Activated inactive user via OIDC + processed pending intent: email={}, role={}", email, targetRole);
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

    // java
    /**
     * Tạo username từ email, nếu bị trùng thì thêm số đuôi.
     * VD: john.doe@gmail.com → "john.doe"
     *     john.doe@outlook.com → "john.doe1" (nếu "john.doe" đã có)
     */
    private String generateUniqueUsername(String email) {
        String base = email.split("@")[0];
        // Thay dấu chấm bằng gạch dưới cho đẹp hơn (tuỳ chọn)
        base = base.replaceAll("[^a-zA-Z0-9_]", "_");

        String username = base;
        int suffix = 1;
        while (userRepository.findByUsername(username).isPresent()) {
            username = base + suffix;
            suffix++;
        }
        return username;
    }

}
