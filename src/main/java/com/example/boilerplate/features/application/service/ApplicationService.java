package com.example.boilerplate.features.application.service;

import com.example.boilerplate.common.constant.ApplicationStatus;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.application.dto.request.UpdateApplicationStatusRequest;
import com.example.boilerplate.features.application.dto.response.ApplicationDetailResponse;
import com.example.boilerplate.features.application.dto.response.ApplicationResponse;
import com.example.boilerplate.features.application.dto.response.ApplicationSummaryResponse;
import com.example.boilerplate.features.application.entity.Application;
import com.example.boilerplate.features.application.querydsl.ApplicationQueryDSL;
import com.example.boilerplate.features.application.repository.ApplicationRepository;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.repository.JobRepository;
import com.example.boilerplate.infrastructure.cloudinary.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {
    private final ApplicationRepository applicationRepository;
    private final EmployeeRepository employeeRepository;
    private final JobRepository jobRepository;
    private final CloudinaryService cloudinaryService;
    private final ApplicationQueryDSL applicationQueryDSL;

    /**
     * Ứng tuyển / cập nhật CV cho một job.
     * <p>
     * - Chưa từng apply → tạo mới, upload CV lên Cloudinary, tăng apply_count
     * - Đã apply rồi → upload CV mới lên Cloudinary, xoá CV cũ, cập nhật thông tin
     * (apply_count không tăng vì chỉ thay CV, không phải người mới)
     *
     * @param userId      ID của user hiện tại
     * @param jobId       ID của job cần apply
     * @param file        File CV (Multipart, có thể null nếu chỉ sửa coverLetter)
     * @param coverLetter Thư xin việc (có thể null)
     * @return ApplicationResponse
     */
    @Transactional
    public ApplicationResponse apply(Long userId, Long jobId,
                                     MultipartFile file, String coverLetter) {
        Employee employee = getEmployeeOrThrow(userId);
        Job job = findActiveJobOrThrow(jobId);

        // Kiểm tra xem đã apply trước đó chưa
        var existingOpt = applicationRepository
                .findByJobIdAndEmployeeId(jobId, employee.getId());

        Application application;
        String oldCvUrl = null;  // Lưu CV cũ để xoá SAU khi save DB thành công
        String uploadedUrl = null; // Track CV mới để cleanup nếu DB fail

        if (existingOpt.isPresent()) {
            // Đã apply rồi → cập nhật
            application = existingOpt.get();

            // Không cho phép sửa CV/coverLetter nếu đơn đã được chốt (ACCEPTED/REJECTED)
            if (application.getStatus() == ApplicationStatus.ACCEPTED
                    || application.getStatus() == ApplicationStatus.REJECTED) {
                throw new AppException(ErrorCode.APPLICATION_ALREADY_FINALIZED);
            }

            oldCvUrl = application.getCvUrl(); // Lưu lại trước khi overwrite

            // Nếu có CV mới → upload lên Cloudinary (xoá cũ sau, khi save OK)
            if (file != null && !file.isEmpty()) {
                uploadedUrl = cloudinaryService.uploadFile(file, "applications/" + jobId + "/cv", CloudinaryService.PDF_TYPE);
                application.setCvUrl(uploadedUrl);
            }

            if (coverLetter != null) {
                application.setCoverLetter(coverLetter);
            }

            log.info("Updated application: employee {} re-applied to job {}",
                    employee.getId(), jobId);
        } else {
            // Chưa apply → tạo mới
            application = new Application();
            application.setJob(job);
            application.setEmployee(employee);
            application.setCoverLetter(coverLetter);
            application.setStatus(ApplicationStatus.PENDING);

            // Upload CV lên Cloudinary nếu có file
            if (file != null && !file.isEmpty()) {
                uploadedUrl = cloudinaryService.uploadFile(file, "applications/" + jobId + "/cv", CloudinaryService.PDF_TYPE);
                application.setCvUrl(uploadedUrl);
            }

            // Cache counter: tăng apply_count
            jobRepository.incrementApplyCount(jobId);

            log.info("Applied: employee {} applied to job {}",
                    employee.getId(), jobId);
        }

        // Save DB — nếu fail thì cleanup orphan file trên Cloudinary
        try {
            applicationRepository.save(application);
        } catch (Exception e) {
            if (uploadedUrl != null) {
                try {
                    cloudinaryService.deleteFile(uploadedUrl);
                    log.warn("Cleaned up orphan CV after DB failure: {}", uploadedUrl);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to cleanup orphan CV: {}", uploadedUrl, cleanupEx);
                }
            }
            throw e;
        }

        // Save DB thành công → giờ mới xoá CV cũ (tránh mất file nếu save fail)
        if (oldCvUrl != null && file != null && !file.isEmpty()) {
            deleteCvFromCloudinary(oldCvUrl);
        }

        return toResponse(application);
    }

    /**
     * Huỷ ứng tuyển — xoá hẳn application khỏi DB + xoá CV trên Cloudinary.
     * <p>
     * Chỉ chủ sở hữu mới được huỷ.
     * Sau khi huỷ → apply_count -= 1.
     */
    @Transactional
    public void cancel(Long userId, Long applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        // Chỉ chủ sở hữu mới được huỷ
        if (!application.getEmployee().getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.APPLICATION_NOT_OWNER);
        }

        Long jobId = application.getJob().getId();

        // Xoá hẳn application khỏi DB
        applicationRepository.delete(application);

        // Xoá CV trên Cloudinary nếu có
        if (application.getCvUrl() != null) {
            deleteCvFromCloudinary(application.getCvUrl());
        }

        // Cache counter: giảm apply_count (không xuống dưới 0)
        jobRepository.decrementApplyCount(jobId);

        log.info("Application {} cancelled by user {}", applicationId, userId);
    }

    /**
     * Lấy danh sách jobs đã apply của user hiện tại (phân trang, mới nhất trước).
     */
    @Transactional(readOnly = true)
    public PaginatedResult<ApplicationResponse> getMyApplications(Long userId, int page, int size) {
        Employee employee = getEmployeeOrThrow(userId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt"));
        Page<Application> appPage = applicationRepository.findByEmployeeIdWithJobFetch(employee.getId(), pageable);
        return PaginatedResult.fromPage(appPage, this::toResponse);
    }

    /**
     * Lấy danh sách ứng viên đã apply vào 1 job (dành cho COMPANY).
     * <p>
     * - Kiểm tra quyền sở hữu: chỉ chủ job mới được xem
     * - Tôn trọng quyền riêng tư: nếu employee có isPublic = false, ẩn thông tin cá nhân
     * - Có filter theo trạng thái (PENDING, REVIEWING, ...) và phân trang
     *
     * @param userId  ID của company user
     * @param jobId   ID của job cần xem ứng viên
     * @param status  Lọc theo trạng thái (optional, null = tất cả)
     * @param page    Số trang
     * @param size    Kích thước trang
     * @return PaginatedResult {@link ApplicationSummaryResponse}
     */
    @Transactional(readOnly = true)
    public PaginatedResult<ApplicationSummaryResponse> getApplicationsByJob(
            Long userId, Long jobId, ApplicationStatus status, int page, int size
    ) {
        // === 1. Kiểm tra job tồn tại ===
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.JOB_NOT_FOUND));
        if (job.isDeleted()) {
            throw new AppException(ErrorCode.JOB_NOT_FOUND);
        }

        // === 2. Verify quyền sở hữu: chỉ COMPANY chủ job mới xem được ===
        if (!job.getCompany().getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        // === 3. Query applications (dùng QueryDSL - fetch join employee + user để tránh N+1) ===
        // appliedAt là cột cần SORT - SORT giảm dần
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt"));
        Page<Application> appPage = applicationQueryDSL.findApplicationsByJob(jobId, status, pageable);
        return PaginatedResult.fromPage(appPage, this::toSummaryResponse);
    }

    /**
     * Xem chi tiết 1 application (dành cho COMPANY).
     * <p>
     * Chỉ chủ sở hữu job mới được xem chi tiết application của job đó.
     * Trả về thông tin đầy đủ của ứng viên + application + job context.
     *
     * @param userId        ID của company user
     * @param applicationId ID của application cần xem
     * @return ApplicationDetailResponse
     */
    @Transactional(readOnly = true)
    public ApplicationDetailResponse getApplicationDetail(Long userId, Long applicationId) {
        // === 1. Tìm application ===
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        // === 2. Verify quyền sở hữu: chỉ COMPANY chủ job mới xem được ===
        if (!application.getJob().getCompany().getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        // === 3. Map DTO ===
        return toFullDetailResponse(application);
    }

    /**
     * Cập nhật trạng thái application (dành cho COMPANY).
     * <p>
     * - Chỉ chủ sở hữu job mới được cập nhật
     * - Đã ACCEPTED → không được đổi status khác (quyết định cuối)
     * - Đã REJECTED → có thể revert lại để xem xét
     * - Không cho phép đặt status = CANCELLED (dành riêng cho employee)
     * - Nếu REJECTED: bắt buộc có rejectedReason
     * - recruiterNote luôn update được, bất kể status hiện tại
     * - Tự động set reviewedAt / respondedAt
     *
     * @param userId        ID của company user
     * @param applicationId ID của application cần cập nhật
     * @param request       DTO chứa status, rejectedReason, recruiterNote
     * @return ApplicationDetailResponse
     */
    @Transactional
    public ApplicationDetailResponse updateApplicationStatus(
            Long userId, Long applicationId, UpdateApplicationStatusRequest request
    ) {
        // === 1. Tìm application ===
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        // === 2. Verify quyền sở hữu ===
        if (!application.getJob().getCompany().getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        ApplicationStatus oldStatus = application.getStatus();
        ApplicationStatus newStatus = request.status();

        // === 3. Validation ===
        // Không cho phép đổi status khác nếu đơn đã ACCEPTED (quyết định cuối)
        // REJECTED vẫn có thể revert lại để xem xét lại
        if (oldStatus == ApplicationStatus.ACCEPTED && newStatus != oldStatus) {
            throw new AppException(ErrorCode.APPLICATION_ALREADY_FINALIZED);
        }

        if (newStatus == ApplicationStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVALID_APPLICATION_STATUS);
        }

        if (newStatus == ApplicationStatus.REJECTED
                && (request.rejectedReason() == null || request.rejectedReason().isBlank())) {
            throw new AppException(ErrorCode.REJECTED_REASON_REQUIRED);
        }

        // === 4. Cập nhật recruitNote (luôn được, bất kể status) ===
        if (request.recruiterNote() != null) {
            application.setRecruiterNote(request.recruiterNote());
        }

        // Nếu chỉ cập nhật recruiterNote, không đổi status → skip phần dưới
        if (newStatus == oldStatus) {
            applicationRepository.save(application);
            log.info("Application {} recruiterNote updated by user {}", applicationId, userId);
            return toFullDetailResponse(application);
        }

        // === 5. Cập nhật status (chỉ khi newStatus != oldStatus) ===
        application.setStatus(newStatus);

        // Nếu revert từ REJECTED sang status khác → xoá rejectedReason + respondedAt
        if (oldStatus == ApplicationStatus.REJECTED && newStatus != ApplicationStatus.REJECTED) {
            application.setRejectedReason(null);
            application.setRespondedAt(null);
        }

        // Gán rejectedReason nếu chuyển sang REJECTED
        if (newStatus == ApplicationStatus.REJECTED) {
            application.setRejectedReason(request.rejectedReason().trim());
        }

        // Set timestamps
        if (newStatus == ApplicationStatus.REVIEWING
                || newStatus == ApplicationStatus.SHORTLISTED) {
            if (application.getReviewedAt() == null) {
                application.setReviewedAt(Instant.now());
            }
        }

        if (newStatus == ApplicationStatus.ACCEPTED
                || newStatus == ApplicationStatus.REJECTED) {
            if (application.getRespondedAt() == null) {
                application.setRespondedAt(Instant.now());
            }
        }

        applicationRepository.save(application);

        log.info("Application {} status updated: {} → {} by user {}",
                applicationId, oldStatus, newStatus, userId);

        return toFullDetailResponse(application);
    }

    // ===== Private helpers =====

    private Employee getEmployeeOrThrow(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    /**
     * Tìm job và kiểm tra:
     * - Job có tồn tại không?
     * - Job đã bị soft delete chưa?
     * - Job có đang ACTIVE không?
     * - Job đã hết hạn chưa?
     */
    private Job findActiveJobOrThrow(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.JOB_NOT_FOUND));

        if (job.isDeleted()) {
            throw new AppException(ErrorCode.JOB_NOT_FOUND);
        }
        if (job.getStatus() != JobStatus.ACTIVE) {
            throw new AppException(ErrorCode.JOB_ALREADY_CLOSED);
        }
        if (job.getExpiryDate().isBefore(LocalDate.now())) {
            throw new AppException(ErrorCode.JOB_EXPIRED);
        }

        return job;
    }

    /**
     * Xoá CV cũ trên Cloudinary, không throw lỗi nếu thất bại
     * (CV không còn trên CDN vẫn không ảnh hưởng đến luồng chính).
     */
    private void deleteCvFromCloudinary(String cvUrl) {
        try {
            cloudinaryService.deleteFile(cvUrl);
        } catch (Exception e) {
            log.warn("Failed to delete old CV from Cloudinary: {}", cvUrl, e);
        }
    }

    private ApplicationResponse toResponse(Application application) {
        return ApplicationResponse.builder()
                .id(application.getId())
                .jobId(application.getJob().getId())
                .jobTitle(application.getJob().getTitle())
                .companyId(application.getJob().getCompany().getId())
                .companyName(application.getJob().getCompany().getName())
                .companyLogoUrl(application.getJob().getCompany().getLogoUrl())
                .employeeId(application.getEmployee().getId())
                .coverLetter(application.getCoverLetter())
                .cvUrl(application.getCvUrl())
                .status(application.getStatus())
                .appliedAt(application.getAppliedAt())
                .build();
    }

    private ApplicationSummaryResponse toSummaryResponse(Application application) {
        Employee employee = application.getEmployee();
        var user = employee.getUser();

        boolean isPublic = Boolean.TRUE.equals(employee.getIsPublic());

        return ApplicationSummaryResponse.builder()
                .id(application.getId())
                .status(application.getStatus())
                .coverLetter(application.getCoverLetter())
                .cvUrl(application.getCvUrl())
                .appliedAt(application.getAppliedAt())
                .employeeId(employee.getId())
                .fullName(isPublic ? user.getFullName() : null)
                .avatarUrl(isPublic ? user.getAvatarUrl() : null)
                .email(isPublic ? user.getEmail() : null)
                .isPublic(isPublic)
                .build();
    }

    private ApplicationDetailResponse toFullDetailResponse(Application application) {
        Employee employee = application.getEmployee();
        var user = employee.getUser();

        boolean isPublic = Boolean.TRUE.equals(employee.getIsPublic());

        return ApplicationDetailResponse.builder()
                // Application
                .id(application.getId())
                .status(application.getStatus())
                .coverLetter(application.getCoverLetter())
                .cvUrl(application.getCvUrl())
                .appliedAt(application.getAppliedAt())
                .recruiterNote(application.getRecruiterNote())
                .rejectedReason(application.getRejectedReason())
                .reviewedAt(application.getReviewedAt())
                .respondedAt(application.getRespondedAt())
                // Employee (nếu private thì ẩn)
                .employeeId(employee.getId())
                .fullName(isPublic ? user.getFullName() : null)
                .avatarUrl(isPublic ? user.getAvatarUrl() : null)
                .email(isPublic ? user.getEmail() : null)
                .phone(isPublic ? employee.getPhone() : null)
                .title(isPublic ? employee.getTitle() : null)
                .bio(isPublic ? employee.getBio() : null)
                .city(isPublic ? employee.getCity() : null)
                .address(isPublic ? employee.getAddress() : null)
                .skills(isPublic ? employee.getSkills() : null)
                .experiences(isPublic ? employee.getExperiences() : null)
                .education(isPublic ? employee.getEducation() : null)
                .githubUrl(isPublic ? employee.getGithubUrl() : null)
                .linkedinUrl(isPublic ? employee.getLinkedinUrl() : null)
                .portfolioUrl(isPublic ? employee.getPortfolioUrl() : null)
                .isPublic(isPublic)
                // Job context
                .jobId(application.getJob().getId())
                .jobTitle(application.getJob().getTitle())
                .companyId(application.getJob().getCompany().getId())
                .companyName(application.getJob().getCompany().getName())
                .companyLogoUrl(application.getJob().getCompany().getLogoUrl())
                .build();
    }
}
