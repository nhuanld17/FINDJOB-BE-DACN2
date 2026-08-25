package com.example.boilerplate.features.company.entity;

import com.example.boilerplate.features.employee.entity.Employee;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Đánh giá của Employee về Company.
 * 
 * Mỗi employee chỉ được đánh giá 1 lần cho 1 công ty (unique constraint ở DB + application layer).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_reviews", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"company_id", "employee_id"})
})
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /** Số sao (1-5) */
    @Column(nullable = false)
    private Integer rating;

    /** Tiêu đề đánh giá (optional) */
    @Column(length = 255)
    private String title;

    /** Nội dung đánh giá */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Ưu điểm (optional) */
    @Column(columnDefinition = "TEXT")
    private String pros;

    /** Nhược điểm (optional) */
    @Column(columnDefinition = "TEXT")
    private String cons;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
