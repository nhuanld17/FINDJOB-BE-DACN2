package com.example.boilerplate.features.application.controller;

import com.example.boilerplate.common.constant.ApplicationStatus;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.application.dto.request.UpdateApplicationStatusRequest;
import com.example.boilerplate.features.application.dto.response.ApplicationDetailResponse;
import com.example.boilerplate.features.application.dto.response.ApplicationResponse;
import com.example.boilerplate.features.application.dto.response.ApplicationStatusResponse;
import com.example.boilerplate.features.application.dto.response.ApplicationSummaryResponse;
import com.example.boilerplate.features.application.service.ApplicationService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    /**
     * Ứng tuyển / cập nhật CV cho một job.
     * <p>
     * Nhận multipart form-data gồm file CV (tuỳ chọn) và coverLetter (tuỳ chọn).
     * - Chưa apply → tạo mới, upload CV lên Cloudinary, tăng apply_count
     * - Đã apply → upload CV mới, xoá CV cũ trên Cloudinary, cập nhật thông tin
     */
    @PostMapping(value = "/jobs/{jobId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<ApplicationResponse>> apply(
            @PathVariable Long jobId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "coverLetter", required = false) String coverLetter,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ApplicationResponse response = applicationService.apply(
                userDetails.getId(), jobId, file, coverLetter
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Huỷ ứng tuyển — xoá hẳn application + xoá CV trên Cloudinary.
     * Chỉ chủ sở hữu mới được huỷ.
     */
    @PostMapping("/{applicationId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> cancel(
            @PathVariable Long applicationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        applicationService.cancel(userDetails.getId(), applicationId);
        return ResponseEntity.ok(APIResponse.success());
    }

    /**
     * [User] Kiểm tra user hiện tại đã ứng tuyển job này chưa.
     * Trả về { isApplied, applicationId, status } — dùng cho nút "Ứng tuyển"
     * trên màn chi tiết job (pattern giống saved-jobs status).
     */
    @GetMapping("/jobs/{jobId}/status")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<ApplicationStatusResponse>> getApplicationStatus(
            @PathVariable Long jobId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ApplicationStatusResponse response = applicationService.getApplicationStatus(
                userDetails.getId(), jobId
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Lấy danh sách job đã apply của user hiện tại.
     * Sắp xếp theo thời gian apply mới nhất trước, có phân trang.
     * <p>
     * Có thể lọc theo trạng thái JOB ({@code jobStatus}) — ACTIVE / EXPIRED / ...
     * Bỏ trống = tất cả. App dùng để hiển thị "Tất cả / Đang tuyển / Hết hạn".
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<PaginatedResult<ApplicationResponse>>> getMyApplications(
            @RequestParam(required = false) JobStatus jobStatus,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        PaginatedResult<ApplicationResponse> response = applicationService.getMyApplications(
                userDetails.getId(), jobStatus, page, size
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * [Company] Lấy danh sách ứng viên đã apply vào 1 job.
     * Chỉ COMPANY chủ sở hữu job mới xem được.
     * Có filter theo trạng thái, phân trang, sort mới nhất trước.
     * <p>
     * Nếu ứng viên để profile private, thông tin cá nhân sẽ bị ẩn.
     */
    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<PaginatedResult<ApplicationSummaryResponse>>> getApplicationsByJob(
            @PathVariable Long jobId,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        PaginatedResult<ApplicationSummaryResponse> response = applicationService.getApplicationsByJob(
                userDetails.getId(), jobId, status, page, size
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * [Company] Xem chi tiết 1 application + thông tin đầy đủ của ứng viên.
     * Chỉ COMPANY chủ sở hữu job mới xem được.
     */
    @GetMapping("/{applicationId}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<ApplicationDetailResponse>> getApplicationDetail(
            @PathVariable Long applicationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ApplicationDetailResponse response = applicationService.getApplicationDetail(
                userDetails.getId(), applicationId
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * [Company] Cập nhật trạng thái application (PENDING → REVIEWING → ACCEPTED/REJECTED).
     * <p>
     * - Khi REJECTED: bắt buộc phải có rejectedReason
     * - recruiterNote: ghi chú nội bộ (optional)
     * - Tự động set reviewedAt / respondedAt
     * - Không cho phép đặt CANCELLED (dành riêng cho employee)
     */
    @PatchMapping("/{applicationId}/status")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<ApplicationDetailResponse>> updateApplicationStatus(
            @PathVariable Long applicationId,
            @RequestBody @jakarta.validation.Valid UpdateApplicationStatusRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ApplicationDetailResponse response = applicationService.updateApplicationStatus(
                userDetails.getId(), applicationId, request
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }
}
