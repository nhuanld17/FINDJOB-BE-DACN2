package com.example.boilerplate.features.employee.repository;

import com.example.boilerplate.features.employee.entity.Certificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    List<Certificate> findByEmployeeId(Long employeeId);
    void deleteByEmployeeId(Long employeeId);
}
