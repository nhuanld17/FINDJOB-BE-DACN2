package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.features.auth.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final TokenBlacklistService tokenBlacklistService;

    private final RequestMatcher publicEndpointsMatcher =
            new OrRequestMatcher(SecurityConfig.publicMatchers());

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicEndpointsMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        // If no token -> skip, let the next filter process
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);
        String email;
        String jti;

        try {
            email = jwtUtil.extractUsername(token);
            jti = jwtUtil.extractJti(token);
        } catch (Exception e) {
            // Token malformed / expired / invalid signature -> Skip
            filterChain.doFilter(request, response);
            return;
        }

        // nếu token đã được thu hồi
        if (jti != null && tokenBlacklistService.isAccessTokenRevoked(jti)) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // Only process if not exist authentication in context
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                CustomUserDetails userDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(email);

                if (jwtUtil.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            } catch (AuthenticationException ex) {
                SecurityContextHolder.clearContext();
                authenticationEntryPoint.commence(request, response, ex);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}

