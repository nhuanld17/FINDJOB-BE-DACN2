package com.example.boilerplate.features.job.entity;

import com.example.boilerplate.common.base.BaseEntity;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "jobs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"company_id", "slug"})
})
public class Job extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String benefits;

    @Column(name = "salary_min", precision = 12, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 12, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency", length = 10)
    private String salaryCurrency = "VND";

    @Column(name = "years_of_experience", length = 30)
    private String yearsOfExperience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Seniority seniority;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private JobType jobType;

    @Column(length = 255)
    private String location;

    @Column(nullable = false, length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skills_required", columnDefinition = "jsonb")
    private List<String> skillsRequired = new ArrayList<>();

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "apply_count")
    private Integer applyCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.ACTIVE;
}
