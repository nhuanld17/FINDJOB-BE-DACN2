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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
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
            "/products/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/actuator/health"
    };

    public static RequestMatcher[] publicMatchers() {
        return Arrays.stream(PUBLIC_PATTERNS)
                .map(AntPathRequestMatcher::new)
                .toArray(RequestMatcher[]::new);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CustomOidcUserService customOidcUserService,
                                                   OidcLoginSuccessHandler oidcLoginSuccessHandler,
                                                   OidcLoginFailureHandler oidcLoginFailureHandler) throws Exception {
        http
                // withDefaults() tự động tìm bean tên "corsConfigurationSource" trong
                // application context và áp dụng CORS từ đó — xem CorsConfig.corsConfigurationSource()
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> {
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
                })
                .exceptionHandling(exception -> {
                    exception.authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler);
                })
                // Spring Security 7 uses PathPatternRequestMatcher instead of antMatchers/mvcMatchers
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATTERNS).permitAll()
                                .anyRequest().authenticated()
                        )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService))
                        .successHandler(oidcLoginSuccessHandler)
                        .failureHandler(oidcLoginFailureHandler)
                )
                .authenticationProvider(daoAuthenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
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

