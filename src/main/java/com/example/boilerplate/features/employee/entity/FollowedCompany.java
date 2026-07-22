package com.example.boilerplate.features.employee.entity;

import com.example.boilerplate.features.company.entity.Company;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "followed_companies")
public class FollowedCompany {

    @EmbeddedId
    private FollowedCompanyId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("employeeId")
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("companyId")
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "followed_at", nullable = false, updatable = false)
    private Instant followedAt;

    @PrePersist
    protected void onCreate() {
        followedAt = Instant.now();
    }
}
