package com.example.boilerplate.features.job.controller;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.job.dto.request.CreateJobRequest;
import com.example.boilerplate.features.job.dto.request.UpdateJobRequest;
import com.example.boilerplate.features.job.dto.response.JobResponse;
import com.example.boilerplate.features.job.service.JobService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /** Tạo job mới — Chỉ COMPANY */
    @PostMapping
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<JobResponse>> createJob(
            @RequestBody @Valid CreateJobRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        JobResponse response = jobService.createJob(userDetails.getId(), request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Lấy chi tiết job — Public */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<JobResponse>> getJobById(@PathVariable Long id) {
        JobResponse response = jobService.getJobById(id);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Cập nhật job — Chỉ COMPANY (chủ sở hữu) */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<JobResponse>> updateJob(
            @PathVariable Long id,
            @RequestBody @Valid UpdateJobRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        JobResponse response = jobService.updateJob(userDetails.getId(), id, request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Xoá job (soft delete) — Chỉ COMPANY (chủ sở hữu) */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<Void>> deleteJob(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        jobService.deleteJob(userDetails.getId(), id);
        return ResponseEntity.ok(APIResponse.success());
    }

    /** Cập nhật trạng thái job (ACTIVE / CLOSED / DRAFT) */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<JobResponse>> updateJobStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String statusStr = body.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            throw new AppException(ErrorCode.BLANK_FIELD);
        }
        JobStatus newStatus;
        try {
            newStatus = JobStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BLANK_FIELD);
        }
        JobResponse response = jobService.updateJobStatus(userDetails.getId(), id, newStatus);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Xem chi tiết job dành cho COMPANY (chủ sở hữu).
     * Không filter status — xem được DRAFT/CLOSED của chính mình.
     */
    @GetMapping("/{id}/owner")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<JobResponse>> getMyJobDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        JobResponse response = jobService.getMyJobDetail(userDetails.getId(), id);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Lấy danh sách job ACTIVE / EXPIRED của một company — Public.
     * Chỉ hiển thị job chưa bị xoá, không hiển thị DRAFT / CLOSED.
     * Có search (theo title) + phân trang.
     */
    @GetMapping("/company/{companyId}")
    public ResponseEntity<APIResponse<PaginatedResult<JobResponse>>> getJobsByCompany(
            @PathVariable Long companyId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PaginatedResult<JobResponse> result = jobService.getJobsByCompany(companyId, search, pageable);
        return ResponseEntity.ok(APIResponse.success(result));
    }

    /**
     * Tìm kiếm job — Public.
     * Trả về các job ACTIVE hoặc EXPIRED, chưa bị xoá.
     * Các tham số filter (city, seniority, jobType) đều optional.
     */
    @GetMapping
    public ResponseEntity<APIResponse<PaginatedResult<JobResponse>>> searchJobs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) City city,
            @RequestParam(required = false) String seniority,
            @RequestParam(required = false) String jobType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PaginatedResult<JobResponse> result = jobService.searchJobs(search, city, seniority, jobType, pageable);
        return ResponseEntity.ok(APIResponse.success(result));
    }
}
