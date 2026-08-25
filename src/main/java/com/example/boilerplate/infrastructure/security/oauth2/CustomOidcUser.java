package com.example.boilerplate.infrastructure.security.oauth2;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;

/**
 * CustomOidcUser — OidcUser mở rộng chứa userId local
 *
 * Vai trò:
 * Khi user đăng nhập bằng Google (OIDC), Spring Security tự động tạo ra một
 * {@link org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser}
 * với các thông tin từ Google (sub, email, name, picture…).
 * Tuy nhiên {@code DefaultOidcUser} chỉ chứa thông tin từ Google —
 * nó không biết user đó có tồn tại trong DB local hay không,
 * userId local là bao nhiêu.
 * 
 * {@code CustomOidcUser} giải quyết vấn đề đó: nó kế thừa {@code DefaultOidcUser}
 * và thêm 2 trường:
 * 
 *   - {@code userId} — ID của user trong DB local (bảng {@code users})
 *   - {@code email} — Email của user (dùng làm subject cho JWT sau này)
 * 
 *
 * Luồng hoạt động:
 * 
 * Google Login (OIDC)
 *      ↓
 * CustomOidcUserService.loadUser()
 *      ↓  (tìm/tạo user trong DB, lấy userId)
 * Tạo CustomOidcUser ← userId local được gắn vào đây
 *      ↓
 * OidcLoginSuccessHandler.onAuthenticationSuccess()
 *      ↓  (đọc userId + email từ CustomOidcUser)
 * Tạo JWT token, set cookie, redirect về FE với ticket
 * 
 *
 * Tại sao cần class này?
 * Nếu chỉ dùng {@code DefaultOidcUser} nguyên bản, sau khi Google xác thực xong,
 * ta chỉ biết email Google của user — không biết userId local để tạo JWT,
 * kiểm tra role, truy vấn dữ liệu liên quan. Class này là "cầu nối" giữa
 * thông tin OIDC từ Google và thông tin user trong DB local.
 * 
 * Ngoài ra, {@link #getName()} override trả về {@code email} thay vì {@code sub}
 * để JWT token sau này dùng email làm subject — nhất quán với form login.
 *
 * @see DefaultOidcUser
 * @see CustomOidcUserService
 * @see OidcLoginSuccessHandler
 */
public class CustomOidcUser extends DefaultOidcUser {

    private final Long userId;
    private final String email;

    public CustomOidcUser(
            Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken,
            OidcUserInfo userInfo,
            Long userId,
            String email
    ) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
        this.email = email;
    }

    public Long getUserId() { return userId; }
    public String getEmail() { return email; }

    @Override
    public String getName() {
        return email; // dùng email làm subject (JWT sub)
    }
}
