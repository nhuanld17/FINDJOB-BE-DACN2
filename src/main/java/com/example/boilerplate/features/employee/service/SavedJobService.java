package com.example.boilerplate.features.employee.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.employee.dto.response.SavedJobResponse;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.entity.SavedJob;
import com.example.boilerplate.features.employee.entity.SavedJobId;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import com.example.boilerplate.features.employee.repository.SavedJobRepository;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SavedJobService {

    private final SavedJobRepository savedJobRepository;
    private final EmployeeRepository employeeRepository;
    private final JobRepository jobRepository;

    /**
     * Lưu một job để xem sau.
     */
    @Transactional
    public void saveJob(Long userId, Long jobId) {
        Employee employee = getEmployeeOrThrow(userId);
        Job job = findJobOrThrow(jobId);

        if (savedJobRepository.existsByEmployeeIdAndJobId(employee.getId(), jobId)) {
            throw new AppException(ErrorCode.ALREADY_SAVED_JOB);
        }

        SavedJob savedJob = new SavedJob();
        savedJob.setId(new SavedJobId(employee.getId(), jobId));
        savedJob.setEmployee(employee);
        savedJob.setJob(job);

        savedJobRepository.save(savedJob);
        log.info("Employee {} saved job {}", employee.getId(), jobId);
    }

    /**
     * Bỏ lưu một job.
     */
    @Transactional
    public void unsaveJob(Long userId, Long jobId) {
        Employee employee = getEmployeeOrThrow(userId);

        if (!savedJobRepository.existsByEmployeeIdAndJobId(employee.getId(), jobId)) {
            throw new AppException(ErrorCode.NOT_SAVED_JOB);
        }

        savedJobRepository.deleteByEmployeeIdAndJobId(employee.getId(), jobId);
        log.info("Employee {} unsaved job {}", employee.getId(), jobId);
    }

    /**
     * Lấy danh sách job đã lưu (phân trang, mới nhất trước).
     */
    @Transactional(readOnly = true)
    public PaginatedResult<SavedJobResponse> getSavedJobs(Long userId, int page, int size) {
        Employee employee = getEmployeeOrThrow(userId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "savedAt"));
        Page<SavedJob> savedPage = savedJobRepository.findByEmployeeIdWithJobFetch(employee.getId(), pageable);
        return PaginatedResult.fromPage(savedPage, this::toResponse);
    }

    /**
     * Kiểm tra user có đang lưu job này không.
     */
    @Transactional(readOnly = true)
    public boolean isSaved(Long userId, Long jobId) {
        Employee employee = getEmployeeOrThrow(userId);
        return savedJobRepository.existsByEmployeeIdAndJobId(employee.getId(), jobId);
    }

    // ===== Private helpers =====

    private Employee getEmployeeOrThrow(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    private Job findJobOrThrow(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.JOB_NOT_FOUND));
        if (job.isDeleted()) {
            throw new AppException(ErrorCode.JOB_NOT_FOUND);
        }
        return job;
    }

    private SavedJobResponse toResponse(SavedJob savedJob) {
        var job = savedJob.getJob();
        return SavedJobResponse.builder()
                .jobId(job.getId())
                .jobTitle(job.getTitle())
                .companyName(job.getCompany().getName())
                .companySlug(job.getCompany().getSlug())
                .companyLogoUrl(job.getCompany().getLogoUrl())
                .savedAt(savedJob.getSavedAt())
                .note(savedJob.getNote())
                .status(job.getStatus())
                .expired(job.getExpiryDate().isBefore(LocalDate.now()))
                .build();
    }
}
