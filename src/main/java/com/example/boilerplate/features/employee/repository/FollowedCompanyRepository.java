package com.example.boilerplate.features.employee.repository;

import com.example.boilerplate.features.employee.entity.FollowedCompany;
import com.example.boilerplate.features.employee.entity.FollowedCompanyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FollowedCompanyRepository extends JpaRepository<FollowedCompany, FollowedCompanyId> {
    List<FollowedCompany> findByEmployeeId(Long employeeId);
    boolean existsByEmployeeIdAndCompanyId(Long employeeId, Long companyId);
    void deleteByEmployeeIdAndCompanyId(Long employeeId, Long companyId);
}
