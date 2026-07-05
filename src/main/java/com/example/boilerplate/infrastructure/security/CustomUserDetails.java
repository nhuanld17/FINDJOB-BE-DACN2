package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.features.user.entity.Role;
import com.example.boilerplate.features.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Getter
public class CustomUserDetails implements UserDetails {
    private final Long id;
    private final String email;
    private final String username;
    private final String password;
    private final String fullName;
    private final boolean active;
    private final boolean deleted;
    private final Set<Role> roles;
    private final List<SimpleGrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        this.id       = user.getId();
        this.email    = user.getEmail();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.fullName = user.getFullName();
        this.active   = user.isActive();
        this.deleted  = user.isDeleted();
        this.roles = user.getRoles();

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
        return username;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;  // isActive = false → locked
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
