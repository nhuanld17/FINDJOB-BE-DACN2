package com.example.boilerplate.common.constant;

/**
 * Oauth2Constant — tập trung toàn bộ key prefix của OAuth2/OIDC flow trong Redis.
 *
 * Trước đây các prefix này nằm private rải rác ở nhiều class
 * ({@code AuthServiceImplement.TICKET_KEY_PREFIX}, {@code RedisOAuth2AuthorizationRequestRepository},
 * ...) nhưng lại bị lặp literal trần ở 2 OIDC handler
 * ({@code OidcLoginSuccessHandler}, {@code OidcLoginFailureHandler}).
 * Nếu đổi prefix ở class nọ mà sót class kia → ticket/state lưu bằng prefix A,
 * đọc bằng prefix B → login Google hỏng ngầm, khó truy vết.
 * Gom về đây để mọi nơi ghi/đọc dùng chung 1 nguồn.
 *
 * Các key liên quan tới OAuth2 state/ticket:
 * 
 *   - {@code oauth2:ticket:{ticket}} → userId — one-time ticket sau Google login, TTL 60s
 *   - {@code oauth2:state:{stateId}} → OAuth2AuthorizationRequest (JDK serializer), TTL 120s
 *   - {@code oauth2:return:{state}} → return_url của mobile, TTL 120s
 * 
 *
 * Phân biệt 2 loại "state" (dễ nhầm → bug khó truy vết):
 * 
 *   - {@link #STATE_PREFIX} dùng key là stateId UUID (sinh trong
 *       {@code RedisOAuth2AuthorizationRequestRepository}, lưu vào cookie {@code oauth2_state})
 *   - {@link #RETURN_PREFIX} dùng key là OAuth 'state' param của Google
 *       ({@code authorizationRequest.getState()}, khác stateId UUID) — xem PLAN_MOBILE_OAUTH.md mục 2
 * 
 */
public final class Oauth2Constant {
    private Oauth2Constant() {}

    /** One-time ticket sau Google login: {@code oauth2:ticket:{ticket}} → userId. */
    public static final String TICKET_PREFIX = "oauth2:ticket:";

    /** OAuth2AuthorizationRequest object: {@code oauth2:state:{stateId}}. */
    public static final String STATE_PREFIX = "oauth2:state:";

    /** return_url của mobile: {@code oauth2:return:{state}}. */
    public static final String RETURN_PREFIX = "oauth2:return:";
}
