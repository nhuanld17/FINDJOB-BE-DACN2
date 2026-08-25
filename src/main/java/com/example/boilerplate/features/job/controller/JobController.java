package com.example.boilerplate.features.job.controller;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.KeysetPage;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.job.dto.request.CreateJobRequest;
import com.example.boilerplate.features.job.dto.request.UpdateJobRequest;
import com.example.boilerplate.features.job.dto.response.JobResponse;
import com.example.boilerplate.features.job.service.JobService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
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
     * Liệt kê job dành cho COMPANY (chủ sở hữu) — KEYSET PAGINATION.
     * 
     * KHÁC với GET /company/{companyId} (public — chỉ ACTIVE/EXPIRED):
     * 
     *   - Chỉ COMPANY gọi được (JWT), lấy companyId từ userId
     *   - Trả TẤT CẢ status (ACTIVE/EXPIRED/DRAFT/CLOSED) nếu không truyền {@code status}
     *   - Lọc theo MỘT HOẶC NHIỀU status: {@code status=ACTIVE,DRAFT}
     *   - Phân trang bằng {@code cursor} (mốc item cuối) thay vì page — keyset
     * 
     * Search theo title, sort CỐ ĐỊNH created_at DESC (mới nhất lên đầu).
     */
    @GetMapping("/manage")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<KeysetPage<JobResponse>>> getMyJobs(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        log.info("############### GET JOB OF COMPANY ID = {}", userDetails.getId());

        if (size < 1 || size > 100) size = 20;

        // Parse status: mỗi phần tử phải là tên enum JobStatus hợp lệ (ACTIVE/EXPIRED/DRAFT/CLOSED)
        List<JobStatus> statuses = null;
        if (status != null && !status.isEmpty()) {
            try {
                statuses = status.stream()
                        .flatMap(s -> Arrays.stream(s.split(",")))   // hỗ trợ cả status=ACTIVE,DRAFT
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> JobStatus.valueOf(s.toUpperCase()))
                        .toList();
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.BLANK_FIELD);
            }
        }

        KeysetPage<JobResponse> result = jobService.getMyJobs(
                userDetails.getId(), statuses, search, cursor, size);
        return ResponseEntity.ok(APIResponse.success(result));
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
     * Các tham số filter (city, seniority, jobType, salaryMin, salaryMax) đều optional.
     * 
     * {@code sort} — cú pháp {@code field,direction} (vd {@code salaryMax,desc}).
     * Field phải nằm trong WHITELIST (xem JobQueryDSL.buildOrderSpecifiers):
     * title, createdAt, updatedAt, salaryMin, salaryMax, expiryDate, city,
     * seniority, jobType. Không hợp lệ → mặc định createdAt DESC.
     */
    @GetMapping
    public ResponseEntity<APIResponse<PaginatedResult<JobResponse>>> searchJobs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) City city,
            @RequestParam(required = false) String seniority,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) Long salaryMin,
            @RequestParam(required = false) Long salaryMax,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // Sort: mặc định mới nhất trước; nếu client gửi sort hợp lệ thì dùng
        // (field không trong whitelist sẽ bị JobQueryDSL đẩy về createdAt DESC)
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        PaginatedResult<JobResponse> result = jobService.searchJobs(
                search, city, seniority, jobType, salaryMin, salaryMax, pageable);
        return ResponseEntity.ok(APIResponse.success(result));
    }

    /**
     * Parse param {@code sort} dạng {@code field,direction} → {@link Sort}.
     * Rỗng/không hợp lệ → mặc định createdAt DESC (mới nhất trước).
     */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (field.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
