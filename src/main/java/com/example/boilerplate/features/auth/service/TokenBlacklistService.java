package com.example.boilerplate.features.auth.service;

public interface TokenBlacklistService {

    public void revokeRefreshToken(String jti, long remainingSeconds);

    public void revokeAccessToken(String jti, long remainingSeconds);

    public boolean isRefreshTokenRevoked(String jti);

    public boolean isAccessTokenRevoked(String jti);
}
