package com.example.boilerplate.features.employee.repository;

import com.example.boilerplate.features.employee.entity.SavedJob;
import com.example.boilerplate.features.employee.entity.SavedJobId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavedJobRepository extends JpaRepository<SavedJob, SavedJobId> {
    List<SavedJob> findByEmployeeId(Long employeeId);
    Page<SavedJob> findByEmployeeId(Long employeeId, Pageable pageable);

    /**
     * JOIN FETCH job + company — tránh N+1 khi toResponse() gọi getJob().getTitle() / getCompany().getName().
     */
    @Query("""
        SELECT sj FROM SavedJob sj
        JOIN FETCH sj.job j
        JOIN FETCH j.company
        WHERE sj.employee.id = :employeeId
    """)
    Page<SavedJob> findByEmployeeIdWithJobFetch(@Param("employeeId") Long employeeId, Pageable pageable);
    boolean existsByEmployeeIdAndJobId(Long employeeId, Long jobId);
    void deleteByEmployeeIdAndJobId(Long employeeId, Long jobId);
}
