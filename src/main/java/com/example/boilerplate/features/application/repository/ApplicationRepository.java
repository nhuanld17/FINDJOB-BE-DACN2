package com.example.boilerplate.features.application.repository;

import com.example.boilerplate.common.constant.ApplicationStatus;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.features.application.entity.Application;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {
    List<Application> findByJobId(Long jobId);
    Page<Application> findByJobId(Long jobId, Pageable pageable);

    Page<Application> findByJobIdAndStatus(Long jobId, ApplicationStatus status, Pageable pageable);

    List<Application> findByEmployeeId(Long employeeId);
    Page<Application> findByEmployeeId(Long employeeId, Pageable pageable);

    /**
     * JOIN FETCH job + company — tránh N+1 khi toResponse() gọi getJob().getTitle() / getCompany().getName().
     */
    @Query("""
        SELECT a FROM Application a
        JOIN FETCH a.job j
        JOIN FETCH j.company
        WHERE a.employee.id = :employeeId
    """)
    Page<Application> findByEmployeeIdWithJobFetch(@Param("employeeId") Long employeeId, Pageable pageable);

    /**
     * Giống {@link #findByEmployeeIdWithJobFetch(Long, Pageable)} nhưng lọc thêm
     * theo trạng thái của JOB (ACTIVE/EXPIRED/...) — dùng cho filter chips
     * "Tất cả / Đang tuyển / Hết hạn" trên màn Việc làm đã ứng tuyển.
     */
    @Query("""
        SELECT a FROM Application a
        JOIN FETCH a.job j
        JOIN FETCH j.company
        WHERE a.employee.id = :employeeId
          AND j.status = :jobStatus
    """)
    Page<Application> findByEmployeeIdWithJobFetchAndJobStatus(
            @Param("employeeId") Long employeeId,
            @Param("jobStatus") JobStatus jobStatus,
            Pageable pageable
    );
    Optional<Application> findByJobIdAndEmployeeId(Long jobId, Long employeeId);
    long countByJobId(Long jobId);
    long countByJobIdAndStatus(Long jobId, String status);
    boolean existsByJobIdAndEmployeeId(Long jobId, Long employeeId);

    /**
     * Kiểm tra đã apply job này chưa (không tính các đơn đã huỷ).
     * Dùng cho re-apply: nếu chỉ có CANCELLED thì được apply lại.
     */
    Optional<Application> findByJobIdAndEmployeeIdAndStatusNot(
            Long jobId, Long employeeId, ApplicationStatus status
    );

    // ==================== Company Dashboard ====================

    /**
     * Đếm tổng số application vào tất cả job của một công ty.
     */
    @Query("SELECT COUNT(a) FROM Application a WHERE a.job.company.id = :companyId")
    long countByCompanyId(@Param("companyId") Long companyId);

    /**
     * Lấy N application gần nhất vào các job của một công ty (JOIN FETCH employee + user + job).
     */
    @Query("""
        SELECT a FROM Application a
        JOIN FETCH a.employee e
        JOIN FETCH e.user
        JOIN FETCH a.job j
        WHERE j.company.id = :companyId
        ORDER BY a.appliedAt DESC
    """)
    List<Application> findTopByCompanyId(@Param("companyId") Long companyId, Pageable pageable);

    /**
     * Đếm số application của một công ty, gom nhóm theo trạng thái.
     */
    @Query("""
        SELECT a.status, COUNT(a) FROM Application a
        WHERE a.job.company.id = :companyId
        GROUP BY a.status
    """)
    List<Object[]> countByStatusGroupByCompanyId(@Param("companyId") Long companyId);
}
