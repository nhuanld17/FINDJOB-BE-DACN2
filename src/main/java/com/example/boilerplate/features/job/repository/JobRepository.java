package com.example.boilerplate.features.job.repository;

import com.example.boilerplate.features.job.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {
    Optional<Job> findByCompanyIdAndSlug(Long companyId, String slug);
    List<Job> findByCompanyId(Long companyId);
    Page<Job> findByStatus(String status, Pageable pageable);
    Page<Job> findByCompanyIdAndStatus(Long companyId, String status, Pageable pageable);
    Page<Job> findByStatusAndCity(String status, String city, Pageable pageable);
    Page<Job> findByStatusAndSeniority(String status, String seniority, Pageable pageable);
    Page<Job> findByStatusAndJobType(String status, String jobType, Pageable pageable);
    long countByCompanyIdAndStatus(Long companyId, String status);
    List<Job> findByStatusAndExpiryDateBefore(String status, LocalDate date);
}
