package com.example.boilerplate.common.exception;

import com.example.boilerplate.common.constant.ErrorCode;
import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String overrideMessage;

    /**
     * Dùng khi message mặc định của ErrorCode là đủ.
     * Ví dụ: throw new AppException(ErrorCode.USER_NOT_FOUND);
     */
    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.overrideMessage = null;
    }

    /**
     * Dùng khi cần message tùy chỉnh
     * Ví dụ: throw new AppException(ErrorCode.BLANK_FIELD, "Email cannot be blank");
     */
    public AppException(ErrorCode errorCode, String customMessage) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.overrideMessage = customMessage;
    }

    /**
     * Lấy message cuối cùng: ưu tiên overrideMessage nếu có.
     */
    public String getResolvedMessage() {
        return overrideMessage != null ? overrideMessage : errorCode.getMessage();
    }
}
