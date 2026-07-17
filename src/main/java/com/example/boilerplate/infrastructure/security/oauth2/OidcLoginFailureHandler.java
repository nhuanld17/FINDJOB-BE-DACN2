package com.example.boilerplate.infrastructure.security.oauth2;

import com.example.boilerplate.common.exception.AccountBannedException;
import com.example.boilerplate.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

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

        // ErrorResponse format: { status, code, message, errors, timestamp }
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(httpStatus, errorCode, message)
        );
    }
}
