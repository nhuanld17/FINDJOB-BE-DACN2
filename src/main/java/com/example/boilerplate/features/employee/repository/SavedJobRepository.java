package com.example.boilerplate.features.employee.repository;

import com.example.boilerplate.features.employee.entity.SavedJob;
import com.example.boilerplate.features.employee.entity.SavedJobId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SavedJobRepository extends JpaRepository<SavedJob, SavedJobId> {
    List<SavedJob> findByEmployeeId(Long employeeId);
    boolean existsByEmployeeIdAndJobId(Long employeeId, Long jobId);
    void deleteByEmployeeIdAndJobId(Long employeeId, Long jobId);
}
