package com.example.boilerplate.infrastructure.security;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.CustomAuthException;
import com.example.boilerplate.common.response.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // CustomAuthException mang ErrorCode -> dùng đúng status + business code của nó
        // (status có thể 401 hoặc 403), cùng format mà GlobalExceptionHandler trả về.
        if (authException instanceof CustomAuthException customAuthException) {
            ErrorCode errorCode = customAuthException.getErrorCode();
            int status = errorCode.getHttpStatusCode().value();

            response.setStatus(status);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    ErrorResponse.of(status, errorCode.getCode(), errorCode.getMessage())
            );
            return;
        }

        // AuthenticationException khác (vd loadUserByUsername ném ra) -> fallback 401 + message, code=null.
        // Lấy message do nơi ném exception cung cấp, fallback "Unauthorized" nếu rỗng.
        String message = authException.getMessage();
        if (message == null || message.isBlank()) {
            message = "Unauthorized";
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(HttpServletResponse.SC_UNAUTHORIZED, message)
        );
    }
}
