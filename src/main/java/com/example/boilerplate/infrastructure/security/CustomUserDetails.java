package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.features.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CustomUserDetails implements UserDetails {
    private final Long id;
    private final String email;
    private final String password;
    private final String fullName;
    private final boolean active;
    private final boolean deleted;
    private final List<SimpleGrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this.id       = user.getId();
        this.email    = user.getEmail();
        this.password = user.getPassword();
        this.fullName = user.getFullName();
        this.active   = user.isActive();
        this.deleted  = user.isDeleted();

        this.authorities = new ArrayList<>();
        for (var role : user.getRoles()) {
            this.authorities.add(new SimpleGrantedAuthority(role.getName().getAuthority()));
        }
    }

    // ===== UserDetails interface =====

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;   // dùng email làm username
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;  // isActive = false → locked
    }

    @Override
    public boolean isEnabled() {
        return active && !deleted; // isDeleted = true → disabled
    }
}
