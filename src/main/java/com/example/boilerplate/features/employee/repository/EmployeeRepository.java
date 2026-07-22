package com.example.boilerplate.features.employee.repository;

import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByUser(User user);
    Optional<Employee> findByUserId(Long userId);
}
