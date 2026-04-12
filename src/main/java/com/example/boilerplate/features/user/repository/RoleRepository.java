package com.example.boilerplate.features.user.repository;

import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.features.user.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role,Long> {
    Optional<Role> findByName(RoleEnum name);
}
