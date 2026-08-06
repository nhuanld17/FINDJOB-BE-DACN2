package com.example.boilerplate.features.application.dto.response;

import com.example.boilerplate.common.constant.ApplicationStatus;
import lombok.Builder;

/**
 * DTO trả về khi USER kiểm tra xem đã ứng tuyển 1 job chưa
 * (pattern giống {@code SaveStatusResponse} của saved-jobs).
 *
 * @param isApplied      User hiện tại đã apply job này chưa
 * @param applicationId  ID của application (null nếu chưa apply)
 * @param status         Trạng thái application hiện tại (null nếu chưa apply)
 * @param cvUrl          Link CV ứng viên đã đính kèm trong đơn (Cloudinary),
 *                       null nếu chưa apply hoặc đơn không có CV —
 *                       app dùng để hiển thị nút Xem / Tải CV
 */
@Builder
public record ApplicationStatusResponse(
        boolean isApplied,
        Long applicationId,
        ApplicationStatus status,
        String cvUrl
) {
}
