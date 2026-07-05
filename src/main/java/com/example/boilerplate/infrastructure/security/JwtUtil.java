package com.example.boilerplate.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    // ========== Generate ==========

    public String generateAccessToken(UserDetails userDetails, String sessionId, String deviceId) {
        return buildToken(userDetails, accessTokenExpirationMs, sessionId, deviceId);
    }

    public String generateRefreshToken(UserDetails userDetails, String sessionId, String deviceId) {
        return buildToken(userDetails, refreshTokenExpirationMs, sessionId, deviceId);
    }

    // ========== Extract ==========
    public String extractSessionId(String token) {

        Object sessionId = extractAllClaims(token).get("sessionId");

        return sessionId != null ? sessionId.toString() : null;
        
    }

    public String extractDeviceId(String token) {
        Object deviceId = extractAllClaims(token).get("deviceId");

        return deviceId != null ? deviceId.toString() : null;
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    public List<String> extractRoles(String token) {
        Object rolesObj = extractAllClaims(token).get("roles");
        if (rolesObj instanceof Collection<?> collection) {
            List<String> roles = new ArrayList<>();
            for (Object item : collection) {
                roles.add(String.valueOf(item));
            }
            return roles;
        }
        return List.of();
    }


    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    // ========== Validate ==========

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        boolean isExpired = extractExpiration(token).before(new Date());
        return username.equals(userDetails.getUsername()) && !isExpired;
    }

    // ========== Private ==========

    private String buildToken(UserDetails userDetails, long expirationMs, String sessionId, String deviceId) {
        Instant now = Instant.now();

        List<String> roles = new ArrayList<>();
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            roles.add(authority.getAuthority());
        }

        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .subject(userDetails.getUsername())
                .claim("roles", roles)
                .claim("sessionId", sessionId)
                .claim("deviceId", deviceId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(getSigningKey())
                .compact();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long remainingTimeOf(String token) {
        long remainingMillis = extractExpiration(token).getTime() - System.currentTimeMillis();
        return remainingMillis / 1000;   // trả giây, khớp revoke*(remainingSeconds, SECONDS)
    }

}

