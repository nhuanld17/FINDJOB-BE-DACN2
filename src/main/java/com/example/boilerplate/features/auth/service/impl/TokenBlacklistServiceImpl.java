package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.features.auth.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String REFRESH_BLACKLIST_PREFIX = "blacklist:refresh:";
    private static final String ACCESS_BLACKLIST_PREFIX = "blacklist:access:";

    // Thu hồi RefreshToken
    @Override
    public void revokeRefreshToken(String jti, long remainingSeconds) {
        redisTemplate.opsForValue().set(REFRESH_BLACKLIST_PREFIX + jti, "revoked", remainingSeconds, TimeUnit.SECONDS);
    }

    // Thu hồi AccessToken
    @Override
    public void revokeAccessToken(String jti, long remainingSeconds) {
        redisTemplate.opsForValue().set(ACCESS_BLACKLIST_PREFIX + jti, "revoked", remainingSeconds, TimeUnit.SECONDS);
    }

    // kiểm tra RefreshToken đã bị thu hồi chưa
    @Override
    public boolean isRefreshTokenRevoked(String jti) {
        return redisTemplate.hasKey(REFRESH_BLACKLIST_PREFIX + jti);
    }

    // Kiểm tra Access Token đã bị thu hồi chưa
    @Override
    public boolean isAccessTokenRevoked(String jti) {
        return redisTemplate.hasKey(ACCESS_BLACKLIST_PREFIX + jti);
    }
}
