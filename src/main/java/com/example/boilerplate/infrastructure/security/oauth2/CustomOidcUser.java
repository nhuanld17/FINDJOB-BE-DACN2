package com.example.boilerplate.infrastructure.security.oauth2;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;

/**
 * OidcUser mở rộng chứa userId local.
 * Dùng để truyền userId từ OidcUserService → SuccessHandler.
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
