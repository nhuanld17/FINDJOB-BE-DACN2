package com.example.boilerplate.features.job.service;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.JobStatus;

import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.KeySetCursor;
import com.example.boilerplate.common.response.KeysetPage;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.job.dto.request.CreateJobRequest;
import com.example.boilerplate.features.job.dto.request.UpdateJobRequest;
import com.example.boilerplate.features.job.dto.response.JobResponse;
import com.example.boilerplate.features.job.entity.Category;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.entity.JobCategory;
import com.example.boilerplate.features.job.entity.JobCategoryId;
import com.example.boilerplate.features.job.querydsl.JobQueryDSL;
import com.example.boilerplate.features.job.repository.CategoryRepository;
import com.example.boilerplate.features.job.repository.JobCategoryRepository;
import com.example.boilerplate.features.job.repository.JobRepository;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.features.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final JobCategoryRepository jobCategoryRepository;
    private final JobQueryDSL jobQueryDSL;

    // ==================== CREATE ====================

    @Transactional
    public JobResponse createJob(Long userId, CreateJobRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Company company = companyRepository.findByOwnerId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }

        // Tạo slug từ title (hỗ trợ tiếng Việt)
        String baseSlug = slugify(request.title());
        String slug = baseSlug;
        int suffix = 1;
        while (jobRepository.findByCompanyIdAndSlug(company.getId(), slug).isPresent()) {
            slug = baseSlug + "-" + suffix;
            suffix++;
        }

        Job job = new Job();
        job.setCompany(company);
        job.setCreatedBy(user);
        job.setTitle(request.title().trim());
        job.setSlug(slug);
        job.setDescription(request.description().trim());
        job.setRequirements(request.requirements().trim());
        job.setBenefits(request.benefits() != null ? request.benefits().trim() : null);
        job.setSalaryMin(request.salaryMin());
        job.setSalaryMax(request.salaryMax());
        job.setSalaryCurrency(request.salaryCurrency() != null ? request.salaryCurrency() : "VND");
        job.setYearsOfExperience(request.yearsOfExperience());
        job.setSeniority(request.seniority());
        job.setJobType(request.jobType());
        job.setLocation(request.location());
        job.setCity(request.city());
        job.setSkillsRequired(request.skillsRequired() != null ? request.skillsRequired() : Collections.emptyList());
        // Kiểm tra ngày hết hạn không được ở quá khứ
        if (request.expiryDate().isBefore(LocalDate.now())) {
            throw new AppException(ErrorCode.EXPIRY_DATE_IN_PAST);
        }
        job.setExpiryDate(request.expiryDate());
        job.setStatus(JobStatus.ACTIVE);

        jobRepository.save(job);

        // Gắn categories
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            for (Long catId : request.categoryIds()) {
                Category cat = categoryRepository.findById(catId)
                        .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
                JobCategory jc = new JobCategory();
                jc.setId(new JobCategoryId(job.getId(), cat.getId()));
                jc.setJob(job);
                jc.setCategory(cat);
                jobCategoryRepository.save(jc);
            }
        }

        log.info("Job created: {} (slug={}) by company {}", job.getTitle(), job.getSlug(), company.getId());
        return toResponse(job);
    }

    // ==================== READ ====================

    @Transactional(readOnly = true)
    public JobResponse getJobById(Long jobId) {
        Job job = findJobOrThrow(jobId);

        if (job.getCompany().isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }

        if (job.getStatus() != JobStatus.ACTIVE && job.getStatus() != JobStatus.EXPIRED) {
            throw new AppException(ErrorCode.INACTIVE_JOB);
        }

        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public PaginatedResult<JobResponse> getJobsByCompany(Long companyId, String search, Pageable pageable) {
        Page<Job> page = jobRepository.findActiveJobsByCompany(
                companyId, List.of(JobStatus.ACTIVE, JobStatus.EXPIRED), search, pageable);

        // Load category cho từng job và tạo map 1 job id <--> list<categoryname>
        Map<Long, List<String>> categoryMap = loadCategoryMapForJobs(page.getContent());

        // Map bằng hàm toResponse 2 tham số
        List<JobResponse> items = page.getContent().stream()
                .map(job -> toResponse(job, categoryMap.getOrDefault(job.getId(), List.of())))
                .toList();

        return PaginatedResult.<JobResponse>builder()
                .items(items)
                .page(page.getNumber())
                .pageSize(page.getSize())
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    @Transactional(readOnly = true)
    public PaginatedResult<JobResponse> searchJobs(
            String search,
            City city,
            String seniority,
            String jobType,
            Long salaryMin,
            Long salaryMax,
            Pageable pageable
    ) {
        // Public search: dùng QueryDSL — luôn filter isDeleted=false, status∈{ACTIVE,EXPIRED}
        // Bỏ qua mọi status override từ client vì lý do bảo mật
        // expired job vẫn hiện trong search để ứng viên có thể xem lại thông tin / lưu lại
        Page<Job> page = jobQueryDSL.searchJobs(search, city, seniority, jobType, salaryMin, salaryMax, pageable);

        // Load category cho từng job và tạo map 1 job id <--> list<categoryname>
        Map<Long, List<String>> categoryMap = loadCategoryMapForJobs(page.getContent());

        // Map bằng hàm toResponse 2 tham số
        List<JobResponse> items = page.getContent().stream()
                .map(job -> toResponse(job, categoryMap.getOrDefault(job.getId(), List.of())))
                .toList();

        return PaginatedResult.<JobResponse>builder()
                .items(items)
                .page(page.getNumber())
                .pageSize(page.getSize())
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    /**
     * Liệt kê job cho COMPANY (chủ sở hữu) — KEYSET PAGINATION.
     * 
     * KHÁC với {@link #getJobsByCompany} (public — chỉ ACTIVE/EXPIRED):
     * 
     *   - Lấy companyId từ userId (JWT) — client không tự chọn company được
     *   - Trả TẤT CẢ status nếu không truyền statuses
     *   - Phân trang bằng CURSOR (mốc) thay vì OFFSET — ổn định khi dữ liệu đổi
     *   - Luôn loại job đã xoá mềm
     * 
     *
     * @param userId   ID user (từ JWT) — tìm công ty của họ
     * @param statuses Danh sách status cần lọc (rỗng/null = tất cả)
     * @param search   Từ khoá tìm theo title (optional)
     * @param cursor   Mốc của item cuối đã đọc (null = trang đầu)
     * @param size     Số item mỗi trang (mặc định 20)
     * @return KeysetPage {@link JobResponse}
     */
    @Transactional(readOnly = true)
    public KeysetPage<JobResponse> getMyJobs(
            Long userId,
            List<JobStatus> statuses,
            String search,
            String cursor,
            int size)
    {
        // Tìm công ty của user hiện tại (chỉ owner mới quản lý được job)
        Company company = companyRepository.findByOwnerId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }

        // Giải mã cursor mã hóa -> cursor (createdAt, id). null -> là trang đầu
        KeySetCursor.Cursor decodedCursor = KeySetCursor.decode(cursor);

        List<Job> jobs = jobQueryDSL.searchManageJobs(
                company.getId(), statuses, search,
                decodedCursor != null ? decodedCursor.createdAt() : null,
                decodedCursor != null ? decodedCursor.id() : null,
                size
        );

        // Kiểm tra còn các item phía sau list job này ko
        boolean hasMore = jobs.size() > size;

        // Nếu sau listjob được lấy ra còn có các job khác ở phía sau
        // lấy đúng size của list đó, nếu có ít hơn -> lấy tất cả job trong listjob
        List<Job> pageJobs = hasMore ? jobs.subList(0, size) : jobs;

        // nextCursor = mốc của item cuối trang này (chỉ khi còn trang sau)
        String nextCursor = null;

        // Nếu còn các job phía sau
        if (hasMore && !pageJobs.isEmpty()) {
            // Lấy ra job cuối cùng của kết quả query phía trên
            Job last = pageJobs.getLast();

            // tính toán mốc và encode cho mốc đó dựa trên createdAt và id
            nextCursor = KeySetCursor.encode(last.getCreatedAt(), last.getId());
        }

        // Load categories cho cả trang bằng 1 query -> tránh N + 1 query
        // Gom gết jobId của trang lại -> query 1 lần lấy job_categories + categoriey
        // -> Nhóm theo jobId thành Map<jobId, List<categoryName>>
        Map<Long, List<String>> categoryMap = loadCategoryMapForJobs(pageJobs);

        // Map sang response - categoryNames lấy từ map, ko query lại từng job
        List<JobResponse> items = pageJobs.stream()
                .map(job -> toResponse(job, categoryMap.getOrDefault(job.getId(), List.of())))
                .toList();

        return KeysetPage.of(items, nextCursor, hasMore);
    }

    /**
     * Xem chi tiết job dành cho COMPANY (chủ sở hữu).
     * Không filter status — COMPANY xem được DRAFT/CLOSED của chính họ.
     * Chỉ check isDeleted + quyền sở hữu.
     */
    @Transactional(readOnly = true)
    public JobResponse getMyJobDetail(Long userId, Long jobId) {
        Job job = findJobOrThrow(jobId);
        verifyOwnership(job, userId);

        // Không check job.getStatus() — COMPANY xem được cả DRAFT/CLOSED
        // Không check company.isDeleted() — vì findJobOrThrow đã check rồi
        // (nếu job tồn tại thì company cũng tồn tại — FK constraint)

        return toResponse(job);
    }

    // ==================== UPDATE ====================

    @Transactional
    public JobResponse updateJob(Long userId, Long jobId, UpdateJobRequest request) {
        Job job = findJobOrThrow(jobId);
        verifyOwnership(job, userId);

        if (request.title() != null) {
            job.setTitle(request.title().trim());
            // Re-generate slug
            String baseSlug = slugify(request.title().trim());
            String slug = baseSlug;
            int suffix = 1;
            while (jobRepository.findByCompanyIdAndSlug(job.getCompany().getId(), slug).isPresent()
                    && !slug.equals(job.getSlug())) {
                slug = baseSlug + "-" + suffix;
                suffix++;
            }
            job.setSlug(slug);
        }
        if (request.description() != null) job.setDescription(request.description().trim());
        if (request.requirements() != null) job.setRequirements(request.requirements().trim());
        if (request.benefits() != null) job.setBenefits(request.benefits().trim());
        if (request.salaryMin() != null) job.setSalaryMin(request.salaryMin());
        if (request.salaryMax() != null) job.setSalaryMax(request.salaryMax());
        if (request.salaryCurrency() != null) job.setSalaryCurrency(request.salaryCurrency());
        if (request.yearsOfExperience() != null) job.setYearsOfExperience(request.yearsOfExperience());
        if (request.seniority() != null) job.setSeniority(request.seniority());
        if (request.jobType() != null) job.setJobType(request.jobType());
        if (request.location() != null) job.setLocation(request.location());
        if (request.city() != null) job.setCity(request.city());
        if (request.skillsRequired() != null) job.setSkillsRequired(request.skillsRequired());
        if (request.expiryDate() != null) {
            if (request.expiryDate().isBefore(LocalDate.now())) {
                throw new AppException(ErrorCode.EXPIRY_DATE_IN_PAST);
            }
            job.setExpiryDate(request.expiryDate());
        }

        jobRepository.save(job);

        // Update categories nếu có
        if (request.categoryIds() != null) {
            jobCategoryRepository.deleteByJobId(job.getId());
            for (Long catId : request.categoryIds()) {
                Category cat = categoryRepository.findById(catId)
                        .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
                JobCategory jc = new JobCategory();
                jc.setId(new JobCategoryId(job.getId(), cat.getId()));
                jc.setJob(job);
                jc.setCategory(cat);
                jobCategoryRepository.save(jc);
            }
        }

        log.info("Job updated: {} (id={})", job.getTitle(), job.getId());
        return toResponse(job);
    }

    // ==================== DELETE ====================

    @Transactional
    public void deleteJob(Long userId, Long jobId) {
        Job job = findJobOrThrow(jobId);
        verifyOwnership(job, userId);
        job.setDeleted(true);
        jobRepository.save(job);
        log.info("Job deleted (soft): {} (id={})", job.getTitle(), job.getId());
    }

    // ==================== STATUS ====================

    @Transactional
    public JobResponse updateJobStatus(Long userId, Long jobId, JobStatus newStatus) {
        Job job = findJobOrThrow(jobId);
        verifyOwnership(job, userId);
        job.setStatus(newStatus);
        jobRepository.save(job);
        log.info("Job status updated: {} → {} (id={})", job.getTitle(), newStatus, job.getId());
        return toResponse(job);
    }

    // ==================== PUBLIC ENTITY ACCESS ====================

    /**
     * Lấy Job entity (raw) — dùng cho ATS service cần thông tin JD.
     * CHỈ trả job chưa xoá mềm (không check status hay quyền sở hữu).
     */
    @Transactional(readOnly = true)
    public Job getJobEntityById(Long jobId) {
        return findJobOrThrow(jobId);
    }

    // ==================== PRIVATE ====================

    private Job findJobOrThrow(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.JOB_NOT_FOUND));

        if (job.isDeleted()) {
            throw new AppException(ErrorCode.JOB_NOT_FOUND);
        }

        return job;
    }

    private void verifyOwnership(Job job, Long userId) {
        if (!job.getCompany().getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    /**
     * Chuyển tiêu đề thành slug, hỗ trợ tiếng Việt (bỏ dấu, giữ nguyên chữ cái).
     */
    private String slugify(String text) {
        if (text == null || text.isBlank()) return "untitled";
        String normalized = Normalizer.normalize(text.trim().toLowerCase(), Normalizer.Form.NFD);
        String slug = normalized
                .replaceAll("\\p{M}", "")          // bỏ dấu (combining diacritical marks)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slug.isBlank() ? "untitled" : slug;
    }

    private JobResponse toResponse(Job job) {

        // Lấy category names
        List<String> categoryNames = jobCategoryRepository.findByJobId(job.getId())
                .stream()
                .map(jc -> jc.getCategory().getName())
                .toList();

        return toResponse(job, categoryNames);
    }

    private Map<Long, List<String>> loadCategoryMapForJobs(List<Job> jobsList) {
        // === Batch load categories cho cả trang
        List<Long> jobIds = jobsList.stream().map(Job::getId).toList();

        return jobCategoryRepository.findByJobIdInWithCategory(jobIds)
                .stream()
                .collect(
                        Collectors.groupingBy(
                                jobCategory -> jobCategory.getJob().getId(),
                                Collectors.mapping(jobCategory -> jobCategory.getCategory().getName(),
                                        Collectors.toList()
                                ))
                );
    }

    private JobResponse toResponse(Job job, List<String> categoryNames) {
        Company company = job.getCompany();

        return JobResponse.builder()
                .id(job.getId())
                .companyId(company.getId())
                .companyName(company.getName())
                .companySlug(company.getSlug())
                .companyLogoUrl(company.getLogoUrl())
                .createdBy(job.getCreatedBy().getId())
                .createdByName(job.getCreatedBy().getFullName())
                .title(job.getTitle())
                .slug(job.getSlug())
                .description(job.getDescription())
                .requirements(job.getRequirements())
                .benefits(job.getBenefits())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryCurrency(job.getSalaryCurrency())
                .yearsOfExperience(job.getYearsOfExperience())
                .seniority(job.getSeniority())
                .jobType(job.getJobType())
                .location(job.getLocation())
                .city(job.getCity())
                .skillsRequired(job.getSkillsRequired())
                .expiryDate(job.getExpiryDate())
                .applyCount(job.getApplyCount())
                .status(job.getStatus())
                .deleted(job.isDeleted())
                .expired(job.getExpiryDate().isBefore(LocalDate.now()))
                .categoryNames(categoryNames)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
