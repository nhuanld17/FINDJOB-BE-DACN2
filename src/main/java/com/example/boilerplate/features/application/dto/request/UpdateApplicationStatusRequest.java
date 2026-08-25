package com.example.boilerplate.features.application.dto.request;

import com.example.boilerplate.common.constant.ApplicationStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request cho COMPANY cập nhật trạng thái application.
 * 
 * - status: bắt buộc (không được để CANCELLED — dành riêng cho employee)
 * - rejectedReason: bắt buộc nếu status = REJECTED
 * - recruiterNote: tuỳ chọn (ghi chú nội bộ của recruiter)
 */
public record UpdateApplicationStatusRequest(
        @NotNull(message = "BLANK_FIELD")
        ApplicationStatus status,

        String rejectedReason,

        String recruiterNote
) {
}
