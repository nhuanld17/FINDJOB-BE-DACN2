package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.features.auth.service.TokenBlacklistService;
import com.example.boilerplate.infrastructure.redis.RedisService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final TokenBlacklistService tokenBlacklistService;

    private final RequestMatcher publicEndpointsMatcher =
            new OrRequestMatcher(SecurityConfig.publicMatchers());
    private final RedisService redisService;

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
        String usernameFromToken;
        String jtiFromToken;
        String sessionIdFromToken;
        String deviceIdFromToken;

        try {
            usernameFromToken = jwtUtil.extractUsername(token);
            jtiFromToken = jwtUtil.extractJti(token);
            sessionIdFromToken = jwtUtil.extractSessionId(token);
            deviceIdFromToken = jwtUtil.extractDeviceId(token);
        } catch (ExpiredJwtException e) {
            // Token hết hạn -> báo code riêng để FE biết đường gọi refresh token
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.ACCESS_TOKEN_EXPIRED, e)
            );
            return;
        } catch (Exception e) {
            // Token malformed / sai chữ ký -> AuthenticationEntryPoint handle
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.UNAUTHENTICATED, e)
            );
            return;
        }

        // nếu token đã được thu hồi
        if (jtiFromToken != null && tokenBlacklistService.isAccessTokenRevoked(jtiFromToken)) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.TOKEN_REVOKED)
            );
            return;
        }

        // Lấy toàn bộ session:{sessionId} trong 1 lần (HGETALL) thay vì gọi Redis
        // nhiều lần. Map rỗng = session không tồn tại (hoặc đã hết TTL).
        Map<Object, Object> session = redisService.getSession(sessionIdFromToken);
        if (session.isEmpty()) {
            // Xóa context và cho authenticationEntryPoint xử lí
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.SESSION_INACTIVE)
            );
            return;
        }

        // status = ACTIVE ko
        Object statusObj = session.get("status");
        if (statusObj == null || !"ACTIVE".equals(statusObj.toString())) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.SESSION_INACTIVE)
            );
            return;
        }

        // Check tiếp: username trong session:{sessionId} có khớp với sub trong token không
        Object usernameObj = session.get("username");
        if (usernameObj == null || !usernameObj.toString().equals(usernameFromToken)) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.UNAUTHENTICATED)
            );
            return;
        }

        // Check: deviceId trong session:{sessionId} có khớp với deviceId claim không.
        Object deviceIdObj = session.get("deviceId");
        if (deviceIdObj == null || !deviceIdObj.toString().equals(deviceIdFromToken)) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new CustomAuthException(ErrorCode.SESSION_DEVICE_MISMATCH)
            );
            return;
        }

        // Only process if not exist authentication in context
        // Chỉ xử lí nếu SecurityContextHolder không chứa Authentication
        // Và username từ token không null
        if (usernameFromToken != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // Lấy CustomerUserDetail từ username
                CustomUserDetails userDetails = userDetailsService.loadByUsername(usernameFromToken);

                // Tách 2 nhánh: tài khoản bị ban (deleted) vs chưa verify OTP (inactive)
                // -> 2 code khác nhau để FE hiển thị thông báo phù hợp
                if (userDetails.isDeleted()) {
                    SecurityContextHolder.clearContext();
                    authenticationEntryPoint.commence(request, response, new CustomAuthException(ErrorCode.ACCOUNT_BANNED));
                    return;
                }
                if (!userDetails.isActive()) {
                    SecurityContextHolder.clearContext();
                    authenticationEntryPoint.commence(request, response, new CustomAuthException(ErrorCode.USER_INACTIVE));
                    return;
                }

                // Kiểm tra token có hợp lệ không
                if (jwtUtil.isTokenValid(token, userDetails)) {

                    // Nếu token hợp lệ thì thì tạo UsernamePasswordAuthenticationToken
                    // với credentials null và đặt vào trong SecurityContextHolder
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // update last seen trong session
                    redisService.updateSessionField(sessionIdFromToken, "lastSeen", LocalDateTime.now().toString());
                } else {
                    // Trường hợp token không hợp lệ
                    // => xóa context của SecurityContextHolder
                    // => Ủy quyền AuthenticationEntryPoint xử lí
                    SecurityContextHolder.clearContext();
                    authenticationEntryPoint.commence(
                            request,
                            response,
                            new CustomAuthException(ErrorCode.UNAUTHENTICATED)
                    );
                    return;
                }
            }

            //
            catch (AuthenticationException ex) {
                SecurityContextHolder.clearContext();
                authenticationEntryPoint.commence(request, response, ex);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}

