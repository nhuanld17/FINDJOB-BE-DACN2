package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.constant.Oauth2Constant;
import com.example.boilerplate.common.exception.AccountBannedException;
import com.example.boilerplate.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;

/**
 * OidcLoginFailureHandler — Xử lý khi Google xác thực OIDC thất bại
 *
 * Vai trò:
 * Khi flow OIDC thất bại (user từ chối cấp quyền, email không verify,
 * account bị banned, internal error…), class này quyết định:
 * 
 *   - Trả về JSON error cho Web (React SPA xử lý hiển thị)
 *   - Redirect về deep link mobile kèm error code (app xử lý trên mobile)
 * 
 *
 * Dual-mode response:
 * 
 * Web (không có return_url):
 *   Response 401/403 JSON:
 *   {
 *     "status": 401,
 *     "code": 3008,
 *     "message": "Xác thực Google thất bại. Vui lòng thử lại."
 *   }
 *
 * Mobile (có return_url + whitelist scheme):
 *   Redirect: findjob://oauth/callback?error=3008
 *   → App mobile đọc param error → hiển thị thông báo lỗi
 * 
 *
 * Phân loại lỗi:
 * 
 *   - AccountBannedException → HTTP 403, code 2007 (ACCOUNT_BANNED)
 *       - User bị banned cố tình login Google → báo "Tài khoản đã bị khóa"
 *   - Mọi lỗi khác → HTTP 401, code 3008 (INVALID_CREDENTIALS)
 *       - Google từ chối, email chưa verify, internal error…
 *       - Không leak chi tiết lỗi ra ngoài (tránh lộ thông tin cho attacker)
 * 
 *
 * Bảo mật:
 * 
 *   - Không leak internal error: Mọi lỗi không phải AccountBannedException
 *       đều gộp vào "Xác thực Google thất bại" — attacker không biết chính xác lý do
 *   - Whitelist scheme: Chỉ redirect về deep link đã cấu hình (VD: {@code findjob://}),
 *       chặn open redirect attack
 *   - GETDEL return_url: Xóa key Redis ngay sau khi đọc — tránh sót state rác
 *   - Log lỗi: Ghi log chi tiết {@code exception.getMessage()} cho debug,
 *       không gửi ra response
 * 
 *
 * Luồng xử lý:
 * 
 * Google trả về lỗi / user hủy xác thực
 *      ↓
 * onAuthenticationFailure()
 *      ↓
 * Kiểm tra exception type
 *      ↓
 * ├── AccountBannedException → httpStatus=403, errorCode=2007
 * └── Other → httpStatus=401, errorCode=3008
 *      ↓
 * Đọc state → GETDEL oauth2:return:{state} → có return_url không?
 *      ↓
 * ├── CÓ + whitelist → Redirect: {returnUrl}?error={errorCode}
 * │     (Mobile app handle)
 * └── KHÔNG → Trả JSON ErrorResponse
 *       (Web React handle)
 * 
 *
 * @see AuthenticationFailureHandler
 * @see com.example.boilerplate.common.exception.AccountBannedException
 * @see com.example.boilerplate.common.response.ErrorResponse
 * @see OidcLoginSuccessHandler
 */
@Slf4j
@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.oauth2.allowed-mobile-schemes:findjob://}")
    private String allowedMobileSchemes;

    @Autowired
    public OidcLoginFailureHandler(ObjectMapper objectMapper,
                                   StringRedisTemplate stringRedisTemplate) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException {
        log.warn("OIDC login failed: {}", exception.getMessage());

        int httpStatus;
        int errorCode;
        String message;

        if (exception instanceof AccountBannedException) {
            httpStatus = 403;
            errorCode = 2007;   // ACCOUNT_BANNED
            message = exception.getMessage();
        } else {
            httpStatus = 401;
            errorCode = 3008;   // INVALID_CREDENTIALS
            message = "Xác thực Google thất bại. Vui lòng thử lại.";
        }

        // Mobile: nếu có return_url, redirect về app kèm error thay vì trả JSON
        String state = request.getParameter("state");
        String returnUrl = (state != null)
                ? stringRedisTemplate.opsForValue().getAndDelete(Oauth2Constant.RETURN_PREFIX + state)
                : null;

        if (returnUrl != null && isAllowedMobileScheme(returnUrl)) {
            String sep = returnUrl.contains("?") ? "&" : "?";
            response.sendRedirect(returnUrl + sep + "error=" + errorCode);
            return;
        }

        // Web (không có return_url): trả JSON như cũ
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(httpStatus, errorCode, message)
        );
    }

    private boolean isAllowedMobileScheme(String url) {
        return Arrays.stream(allowedMobileSchemes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(url::startsWith);
    }
}
