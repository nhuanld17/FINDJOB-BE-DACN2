package com.example.boilerplate.features.auth.service;

public interface OtpService {

    // ───────────────────────── Generate ──────────────────────────────────
    String generateOtp();

    // ───────────────────────── Save / Get ────────────────────────────────
    void saveOtp(Long userId, String otp);
    String getOtp(Long userId);
    void deleteOtp(Long userId);

    // ───────────────────────── Cooldown ──────────────────────────────────
    void setCooldown(Long userId);
    long getCooldownTtl(Long userId);

    // ───────────────────────── Attempts ──────────────────────────────────
    long incrementAttempts(Long userId);
    int getAttempts(Long userId);
    long getAttemptsTtl(Long userId);
    boolean isAttemptBlocked(Long userId);

    // ───────────────────────── Wrong ─────────────────────────────────────
    long incrementWrong(Long userId);
    void resetWrong(Long userId);
    int getWrong(Long userId);
    boolean isWrongBlocked(Long userId);

    // ───────────────────────── Clear ─────────────────────────────────────
    void clearAll(Long userId);
    void clearOtpSessionKeepAttempts(Long userId, String token);
}
