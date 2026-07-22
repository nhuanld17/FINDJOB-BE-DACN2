package com.example.boilerplate.features.application.repository;

import com.example.boilerplate.features.application.entity.Application;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {
    List<Application> findByJobId(Long jobId);
    Page<Application> findByJobId(Long jobId, Pageable pageable);
    List<Application> findByEmployeeId(Long employeeId);
    Page<Application> findByEmployeeId(Long employeeId, Pageable pageable);
    Optional<Application> findByJobIdAndEmployeeId(Long jobId, Long employeeId);
    long countByJobId(Long jobId);
    long countByJobIdAndStatus(Long jobId, String status);
    boolean existsByJobIdAndEmployeeId(Long jobId, Long employeeId);
}
