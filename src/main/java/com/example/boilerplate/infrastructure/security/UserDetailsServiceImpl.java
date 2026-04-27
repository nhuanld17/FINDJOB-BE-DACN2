package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull UserDetails loadUserByUsername(@NonNull String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        if (user.isDeleted()) {
            // AuthenticationEntryPoint sẽ bắt được exception này và xử lí
            // để dịch sang json response
            throw new LockedException("Account banned");
        }

        if (!user.isActive()) {
            // AuthenticationEntryPoint sẽ bắt được exception này và xử lí
            // để dịch sang json response
            throw new DisabledException("User inactive");
        }

        return new CustomUserDetails(user);
    }
}
