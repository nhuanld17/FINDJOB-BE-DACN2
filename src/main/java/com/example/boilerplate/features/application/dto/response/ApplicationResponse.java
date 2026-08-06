package com.example.boilerplate.features.application.dto.response;

import com.example.boilerplate.common.constant.ApplicationStatus;
import com.example.boilerplate.common.constant.JobStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record ApplicationResponse(
        Long id,
        Long jobId,
        String jobTitle,
        Long companyId,
        String companyName,
        String companyLogoUrl,
        Long employeeId,
        String coverLetter,
        String cvUrl,
        ApplicationStatus status,
        Instant appliedAt,
        // Trạng thái của JOB mà user đã ứng tuyển (khác với status của ĐƠN).
        // Dùng để UI phân biệt job còn tuyển (ACTIVE) hay đã hết hạn (EXPIRED).
        JobStatus jobStatus,
        // Job đã hết hạn theo expiryDate chưa (BE tính so với hôm nay).
        // Dùng để hiển thị badge "Hết hạn" chính xác kể cả khi scheduler
        // chưa kịp đổi status ACTIVE → EXPIRED.
        boolean expired
) {
}
