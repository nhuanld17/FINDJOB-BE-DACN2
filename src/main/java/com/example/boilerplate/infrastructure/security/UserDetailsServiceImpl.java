package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;



@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /*
     * NOTE: Không bắt 2 exception LockedException (khi tài khoản bị khóa)
     * và DisabledException (khi bị vô hiệu hóa, do chưa verify otp) vì
     * Khi xác thực, hàm authenticate() sẽ gọi hàm retrieveUser(), hàm
     * retrieveUser() sẽ gọi loadUserByUsername, khi đó nếu loadUserByUsername()
     * ném 2 exception này, sẽ bị retrieveUser bắt lại và ném ra 1 exception chung
     * là InternalAuthenticationException, lúc này nếu API login dùng authenticate()
     * để xác thực sẽ ko thể bắt được 2 exception này khiến cho các thao tác nghiệp
     * vụ không thể được thực hiện đúng cách
     */
    @Override
    @Transactional(readOnly = true)
    public @NonNull UserDetails loadUserByUsername(@NonNull String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        return new CustomUserDetails(user);
    }

    /**
     * Nạp user theo USERNAME (không phải email) — dùng cho JwtAuthFilter:
     * access token mang sub = username, nên filter phải tra theo username.
     * JOIN FETCH roles để build authorities an toàn (roles đã eager).
     */
    @Transactional(readOnly = true)
    public CustomUserDetails loadByUsername(String username) {
        User user = userRepository.findByUsernameWithRoles(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new CustomUserDetails(user);
    }
}
