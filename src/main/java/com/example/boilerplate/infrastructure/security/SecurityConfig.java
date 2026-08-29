package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.infrastructure.security.jwt.JwtAuthFilter;
import com.example.boilerplate.infrastructure.security.oauth2.CustomOidcUserService;
import com.example.boilerplate.infrastructure.security.oauth2.OidcLoginFailureHandler;
import com.example.boilerplate.infrastructure.security.oauth2.OidcLoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * @EnableMethodSecurity: Nó bật Method Security — tức là cho phép bạn bảo vệ từng method bằng annotation.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;
    private final UserDetailsService userDetailsService;

    public static final String[] PUBLIC_PATTERNS = {
            "/api/v1/auth/**",
            "/oauth2/**",
            "/login/oauth2/code/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/actuator/health",
            "/api/test/outbox/send"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CustomOidcUserService customOidcUserService,
                                                   OidcLoginSuccessHandler oidcLoginSuccessHandler,
                                                   OidcLoginFailureHandler oidcLoginFailureHandler,
                                                   RedisOAuth2AuthorizationRequestRepository cookieRepo,
                                                   ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http
                // withDefaults() tự động tìm bean tên "corsConfigurationSource" trong
                // application context và áp dụng CORS từ đó — xem CorsConfig.corsConfigurationSource()
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(accessDeniedHandler))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/change-password").authenticated()
                        .requestMatchers(PUBLIC_PATTERNS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(auth -> auth
                                .authorizationRequestRepository(cookieRepo)
                                .authorizationRequestResolver(pkceResolver(clientRegistrationRepository)))

                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService))

                        .successHandler(oidcLoginSuccessHandler)
                        .failureHandler(oidcLoginFailureHandler)
                )
                .authenticationProvider(daoAuthenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Tạo {@link DefaultOAuth2AuthorizationRequestResolver} với PKCE (Proof Key for Code Exchange).
     *
     * PKCE thêm {@code code_challenge} + {@code code_challenge_method=S256} vào authorization
     * request gửi lên Google, và lưu {@code code_verifier} trong attributes của
     * {@code OAuth2AuthorizationRequest}. Khi Google gửi callback, Spring Security tự động
     * dùng {@code code_verifier} để exchange authorization code lấy token.
     *
     * Mặc dù backend là confidential client (đã có {@code client_secret}), PKCE vẫn là
     * best practice — bảo vệ authorization code ngay cả khi secret bị leak.
     *
     * code_verifier được lưu trong {@code OAuth2AuthorizationRequest.attributes} (String),
     * do đó JDK serialization qua Redis vẫn hoạt động bình thường.
     */
    private DefaultOAuth2AuthorizationRequestResolver pkceResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        "/oauth2/authorization"
                );
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce()
        );
        return resolver;
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(daoAuthenticationProvider());
    }
}

