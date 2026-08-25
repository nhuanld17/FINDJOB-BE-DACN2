package com.example.boilerplate.features.job.repository;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.features.job.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {
    /**
     * Tìm job của một công ty theo companyId và slug
     *
     * @param companyId ID của công ty
     * @param slug      slug của job
     * @return Optional chứa job nếu tìm thấy
     */
    Optional<Job> findByCompanyIdAndSlug(Long companyId, String slug);

    /**
     * Lấy tất cả job của một công ty (không phân trang)
     *
     * @param companyId ID của công ty
     * @return danh sách job thuộc công ty
     */
    List<Job> findByCompanyId(Long companyId);

    /**
     * Lấy tất cả job của một công ty (có phân trang)
     *
     * @param companyId ID của công ty
     * @param pageable  thông tin phân trang
     * @return trang kết quả các job thuộc công ty
     */
    Page<Job> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Lấy danh sách job ACTIVE / EXPIRED, chưa bị xoá của một công ty (có phân trang + search).
     * Dùng cho Employee/ứng viên xem danh sách job của công ty.
     *
     * @param companyId ID của công ty
     * @param statuses  danh sách trạng thái job (thường là [ACTIVE, EXPIRED])
     * @param search    từ khoá tìm kiếm theo title (không dấu, không phân biệt hoa thường)
     * @param pageable  thông tin phân trang
     * @return trang kết quả các job thoả mãn điều kiện
     */
    @Query("""
        SELECT j FROM Job j
        JOIN FETCH j.company
        WHERE j.company.id = :companyId
        AND j.deleted = false
        AND j.status IN :statuses
        AND (:search IS NULL OR :search = '' OR LOWER(j.title) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    Page<Job> findActiveJobsByCompany(@Param("companyId") Long companyId,
                                      @Param("statuses") List<JobStatus> statuses,
                                      @Param("search") String search,
                                      Pageable pageable);

    /**
     * Lấy danh sách job theo trạng thái (có phân trang)
     *
     * @param status   trạng thái job (ACTIVE, EXPIRED, DRAFT, ...)
     * @param pageable thông tin phân trang
     * @return trang kết quả các job có trạng thái chỉ định
     */
    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    /**
     * Lấy danh sách job theo công ty và trạng thái (có phân trang)
     *
     * @param companyId ID của công ty
     * @param status    trạng thái job (ACTIVE, EXPIRED, DRAFT, ...)
     * @param pageable  thông tin phân trang
     * @return trang kết quả các job thoả mãn điều kiện
     */
    Page<Job> findByCompanyIdAndStatus(Long companyId, JobStatus status, Pageable pageable);

    /**
     * Lấy danh sách Job theo trạng thái và thành phố (có phân trang)
     *
     * @param status   trạng thái job (ACTIVE, EXPIRED, DRAFT, ...)
     * @param city     thành phố cần lọc
     * @param pageable thông tin phân trang
     * @return trang kết quả các job thoả mãn điều kiện
     */
    Page<Job> findByStatusAndCity(JobStatus status, City city, Pageable pageable);

    /**
     * Lấy danh sách Job theo Status và cấp bậc (seniority) - phân trang
     *
     * @param status    trạng thái job (ACTIVE, EXPIRED, DRAFT, ...)
     * @param seniority cấp bậc (Junior, Senior, Lead, ...)
     * @param pageable  thông tin phân trang
     * @return trang kết quả các job phù hợp
     */
    Page<Job> findByStatusAndSeniority(JobStatus status, String seniority, Pageable pageable);

    /**
     * Lấy danh sách Job theo Status và loại hình công việc (jobType) - phân trang
     *
     * @param status  trạng thái job (ACTIVE, EXPIRED, DRAFT, ...)
     * @param jobType loại hình công việc (FULL_TIME, PART_TIME, REMOTE, ...)
     * @param pageable thông tin phân trang
     * @return trang kết quả các job phù hợp
     */
    Page<Job> findByStatusAndJobType(JobStatus status, String jobType, Pageable pageable);

    /**
     * Đếm tổng số job của một công ty (chưa bị xoá mềm)
     */
    long countByCompanyIdAndDeletedFalse(Long companyId);

    /**
     * Đếm số lượng job ACTIVE, chưa hết hạn, chưa bị xoá của một công ty
     */
    @Query("""
        SELECT COUNT(j) FROM Job j
        WHERE j.company.id = :companyId
        AND j.deleted = false
        AND j.status = :status
        AND j.expiryDate >= CURRENT_DATE
    """)
    long countActiveByCompanyId(@Param("companyId") Long companyId, @Param("status") JobStatus status);

    /**
     * Đếm số lượng job của một công ty theo trạng thái
     *
     * @param companyId ID của công ty
     * @param status    trạng thái job cần đếm
     * @return số lượng job thoả mãn điều kiện
     */
    long countByCompanyIdAndStatus(Long companyId, JobStatus status);

    /**
     * Lấy danh sách job có trạng thái nhất định và hết hạn trước một ngày cụ thể
     * 
     * Thường được dùng để tự động cập nhật trạng thái job hết hạn (cron job / scheduler).
     *
     * @param status trạng thái job hiện tại
     * @param date   mốc thời gian (ngày) để so sánh expiryDate
     * @return danh sách các job sắp/hết hạn
     */
    List<Job> findByStatusAndExpiryDateBefore(JobStatus status, LocalDate date);

    /**
     * Tìm kiếm job bằng full-text search trên PostgreSQL.
     * 
     * Sử dụng {@code to_tsvector / plainto_tsquery} để tìm kiếm theo title, description và requirements.
     * Kết quả được sắp xếp theo độ liên quan ({@code ts_rank}) giảm dần.
     * Chỉ lấy các job chưa bị xoá mềm ({@code is_deleted = false}), có status ACTIVE hoặc EXPIRED.
     *
     * @param search   từ khoá tìm kiếm (plain text, không cần format đặc biệt)
     * @param pageable thông tin phân trang
     * @return trang kết quả các job khớp với từ khoá tìm kiếm
     */
    @Query(value = """
        SELECT j.* FROM jobs j
        WHERE j.is_deleted = false
        AND j.status IN ('ACTIVE', 'EXPIRED')
        AND to_tsvector('english', j.title || ' ' || coalesce(j.description, '') || ' ' || coalesce(j.requirements, '')) @@ plainto_tsquery('english', :search)
        ORDER BY ts_rank(to_tsvector('english', j.title || ' ' || coalesce(j.description, '') || ' ' || coalesce(j.requirements, '')), plainto_tsquery('english', :search)) DESC
    """, nativeQuery = true, countQuery = """
        SELECT count(*) FROM jobs j
        WHERE j.is_deleted = false
        AND j.status IN ('ACTIVE', 'EXPIRED')
        AND to_tsvector('english', j.title || ' ' || coalesce(j.description, '') || ' ' || coalesce(j.requirements, '')) @@ plainto_tsquery('english', :search)
    """)
    Page<Job> searchByText(@Param("search") String search, Pageable pageable);

    @Modifying
    @Query("UPDATE Job j SET j.applyCount = j.applyCount + 1 WHERE j.id = :jobId")
    void incrementApplyCount(@Param("jobId") Long jobId);

    @Modifying
    @Query("UPDATE Job j SET j.applyCount = GREATEST(j.applyCount - 1, 0) WHERE j.id = :jobId")
    void decrementApplyCount(@Param("jobId") Long jobId);
}
