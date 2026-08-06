package com.example.boilerplate.features.job.repository;

import com.example.boilerplate.features.job.entity.JobCategory;
import com.example.boilerplate.features.job.entity.JobCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobCategoryRepository extends JpaRepository<JobCategory, JobCategoryId> {
    List<JobCategory> findByJobId(Long jobId);
    void deleteByJobId(Long jobId);

    /**
     * Batch query — load toàn bộ job_categories + category cho một list jobId
     * bằng 1 query (JOIN FETCH category) để tránh N+1 trong các endpoint list job.
     */
    @Query("""
        SELECT jc FROM JobCategory jc
        JOIN FETCH jc.category
        WHERE jc.job.id IN :jobIds
    """)
    List<JobCategory> findByJobIdInWithCategory(@Param("jobIds") List<Long> jobIds);
}
