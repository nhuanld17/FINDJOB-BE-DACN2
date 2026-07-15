package com.example.boilerplate.common.exception;

import com.example.boilerplate.common.constant.ErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * AuthenticationException mang theo ErrorCode để JwtAuthEntryPoint dựng được
 * ErrorResponse có business code + đúng HTTP status (giống GlobalExceptionHandler),
 * thay vì chỉ trả 401 + message như mặc định.
 */
@Getter
public class CustomAuthException extends AuthenticationException {

    private final ErrorCode errorCode;

    public CustomAuthException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public CustomAuthException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
