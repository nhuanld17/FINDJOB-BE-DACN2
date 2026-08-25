package com.example.boilerplate.common.constant;

public final class JwtConstant {
    private JwtConstant() {}

    public static final String BEARER_PREFIX = "Bearer ";
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Claim {@code roles} trong JWT — danh sách quyền (số nhiều, mảng String). */
    public static final String CLAIM_ROLES = "roles";

    /** Claim {@code deviceId} trong JWT — ID thiết bị đăng nhập, dùng để đối chiếu session. */
    public static final String CLAIM_DEVICE_ID = "deviceId";

    /** Claim {@code sessionId} trong JWT — ID phiên đăng nhập, dùng để đối chiếu Redis. */
    public static final String CLAIM_SESSION_ID = "sessionId";
}

