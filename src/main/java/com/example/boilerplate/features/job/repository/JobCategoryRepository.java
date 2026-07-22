package com.example.boilerplate.features.job.repository;

import com.example.boilerplate.features.job.entity.JobCategory;
import com.example.boilerplate.features.job.entity.JobCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobCategoryRepository extends JpaRepository<JobCategory, JobCategoryId> {
    List<JobCategory> findByJobId(Long jobId);
    List<JobCategory> findByCategoryId(Long categoryId);
    void deleteByJobId(Long jobId);
}
