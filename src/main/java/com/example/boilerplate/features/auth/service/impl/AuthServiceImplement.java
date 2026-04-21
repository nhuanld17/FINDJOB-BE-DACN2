package com.example.boilerplate.features.auth.service.impl;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.features.auth.dto.request.RegisterRequest;
import com.example.boilerplate.features.auth.service.AuthService;
import com.example.boilerplate.features.auth.service.OtpService;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.UserRepository;
import com.example.boilerplate.infrastructure.mail.EmailService;
import com.example.boilerplate.infrastructure.redis.RedisService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImplement implements AuthService {

    private static final long PENDING_TTL_MINUTES = 10L;

    private final RedisService redisService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final OtpService otpService;

    @Override
    @Transactional
    public void register(RegisterRequest request, HttpServletResponse response, String pendingToken) {

        // password and confirm password miss match
        if (!request.password().trim().equals(request.confirmPassword().trim())) {
            throw new AppException(ErrorCode.PASSWORD_MISMATCH);
        }

        // email was used by an active account
        if (userRepository.isEmailAlreadyInUse(request.email().trim().toLowerCase())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_IN_USE);
        }

        // email was used by an banned account
        if (userRepository.isEmailBanned(request.email().trim().toLowerCase())) {
            throw new AppException(ErrorCode.ACCOUNT_BANNED);
        }

        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();

        // Check whether new username was used by another account
        if (userRepository.isUserNameAlreadyInUse(username, email)) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_IN_USE);
        }

        User user;

        // email was used by an inactive account - (account that have not verified otp yet)
        // update new information for old record
        if (userRepository.isInactiveAccount(email)) {
            user = userRepository.findByEmail(email).orElseThrow(
                    () -> new AppException(ErrorCode.EMAIL_ALREADY_IN_USE)
            );

            user.setUsername(username);
            user.setFullName(request.fullName().trim());
            user.setPassword(passwordEncoder.encode(request.password().trim()));
            user.setActive(false);
            user.setDeleted(false);
            userRepository.save(user);
        }
        // Create new user
        else  {
            user = new User();
            user.setUsername(username);
            user.setFullName(request.fullName().trim());
            user.setPassword(passwordEncoder.encode(request.password().trim()));
            user.setEmail(email);
            user.setActive(false);
            user.setDeleted(false);
            userRepository.save(user);
        }

        // ========= Create & Send OTP ==========

        // if the number of OTP attempts exceeds 5 -> block sending otp
        // user need to wait for 1h-window end to receive new otp code
        if (otpService.isAttemptBlocked(user.getId())) {
            throw new AppException(ErrorCode.TOO_MANY_OTP_ATTEMPTS);
        }

        // Get cooldown ttl of user
        long cooldownTTl = otpService.getCooldownTtl(user.getId());

        // if still within cooldown period -> No new otp created
        // resend pending token for user
        if (cooldownTTl > 0) {
            // Get pending token from redis and send to client, while also renewing
            // TTL for both pending token and reverse token
            String token = resolveOrCreatePendingToken(user.getId(), pendingToken);

            // After renewing TTL for pending token in redis, create pending token in
            // cookie and attach it to the response
            writePendingCookie(response, token);

            return;
        }

        /**
         * When cooldown ended and user did not exceed attempts threshold
         * System provides new otp: rotate pending token, create new otp and send to user's email
         */
        String token = rotatePendingToken(user.getId());
        writePendingCookie(response, token);

        // Create otp code and update guard state
        String otp = otpService.generateOtp();

        // Save otp code
        otpService.saveOtp(user.getId(), otp);

        // set cool down 60s after send otp to client
        otpService.setCooldown(user.getId());

        // increase attempt one unit
        otpService.incrementAttempts(user.getId());

        // Reset the number of incorrect OTP attempts to 0.
        otpService.resetWrong(user.getId());

        // Gửi emai thông báo
        emailService.sendOtpEmail(email, username, otp);
    }

    private String rotatePendingToken(Long id) {
        // Delete old key if exists
        String oldToken = redisService.getString("pending:user:" + id);
        if (oldToken != null && !oldToken.isBlank()) {
            redisService.delete("pending:" + oldToken);
        }

        String newToken = UUID.randomUUID().toString();

        redisService.set("pending:" + newToken, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        redisService.set("pending:user:" + id, newToken, PENDING_TTL_MINUTES, TimeUnit.MINUTES);

        return newToken;
    }

    private void writePendingCookie(HttpServletResponse response, String token) {
        ResponseCookie pendingCookie = ResponseCookie.from("pendingToken", token)
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(600)
                .build();

        response.addHeader("Set-Cookie", pendingCookie.toString());
    }

    private String resolveOrCreatePendingToken(Long id, String pendingToken) {

        String mappedToken = redisService.getString("pending:user:" + id);

        // Prioritize using pending token from redis like source of truth
        if (mappedToken != null && !mappedToken.isBlank()) {

            // renew TTL for pending token and reverse token
            redisService.set("pending:" + mappedToken, id.toString(),PENDING_TTL_MINUTES, TimeUnit.MINUTES);
            redisService.set("pending:user:" + id, mappedToken, PENDING_TTL_MINUTES, TimeUnit.MINUTES);

            return mappedToken;
        }

        // Reuse pendingToken from request if exists
        pendingToken = (pendingToken != null && !pendingToken.isBlank()) ?
                pendingToken : UUID.randomUUID().toString();

        redisService.set("pending:" + pendingToken, id.toString(), PENDING_TTL_MINUTES, TimeUnit.MINUTES);
        redisService.set("pending:user:" + id, pendingToken, PENDING_TTL_MINUTES, TimeUnit.MINUTES);

        return pendingToken;
    }
}
