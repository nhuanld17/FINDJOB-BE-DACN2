package com.example.boilerplate.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(

        // KHÔNG còn @NotBlank — user Google (authProvider != LOCAL, password = null)
        // KHÔNG có mật khẩu cũ → gửi null/rỗng là hợp lệ.
        // Service tự quyết định: có password mới check oldPassword, không có thì bỏ qua.
        @Size(max = 72, message = "INVALID_PASSWORD")
        String oldPassword,

        @NotBlank(message = "BLANK_FIELD")
        @Size(min = 8, max = 72, message = "INVALID_PASSWORD")
        String newPassword
) {
}
