package com.example.boilerplate.infrastructure.security.jwt;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        // ⚠️ Fix Bug 2: trả ErrorResponse cùng format GlobalExceptionHandler/JwtAuthEntryPoint
        // (trước đây trả Map.of("status"/"error"/"message") — thiếu code + timestamp, khác shape).
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        int status = errorCode.getHttpStatusCode().value();

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(status, errorCode.getCode(), errorCode.getMessage())
        );
    }
}

