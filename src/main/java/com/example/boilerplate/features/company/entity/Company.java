package com.example.boilerplate.features.company.entity;

import com.example.boilerplate.common.base.BaseEntity;
import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.features.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "companies")
public class Company extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private User owner;   // Chủ sở hữu công ty (user có role COMPANY)

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "cover_url", length = 500)
    private String coverUrl;

    @Column
    private String website;

    @Column(name = "company_size", length = 50)
    private String companySize;

    @Column(length = 100)
    private String industry;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private City city;

    @Column(length = 500)
    private String address;

    @Column(length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "facebook_url", length = 255)
    private String facebookUrl;

    @Column(name = "linkedin_url", length = 255)
    private String linkedinUrl;

    @Column(name = "contact_position", length = 255)
    private String contactPosition;

    @Column(name = "follower_count", nullable = false)
    private Integer followerCount = 0;

    @Column(name = "average_rating", nullable = false)
    private Double averageRating = 0.0;

    @Column(name = "total_reviews", nullable = false)
    private Integer totalReviews = 0;
}
