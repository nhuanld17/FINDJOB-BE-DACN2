package com.example.boilerplate.features.company.repository;

import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByOwner(User owner);
    Optional<Company> findBySlug(String slug);

    /**
     * JOIN FETCH owner + roles — thay thế 2 query (user + company) bằng 1 query.
     * Dùng trong getCompanyById để tránh N+1 khi toResponse() gọi company.getOwner().getUsername().
     */
    @Query("""
        SELECT DISTINCT c FROM Company c
        JOIN FETCH c.owner o
        LEFT JOIN FETCH o.roles
        WHERE c.id = :id
    """)
    Optional<Company> findByIdWithOwner(@Param("id") Long id);

    /**
     * JOIN FETCH owner + roles — tìm company theo slug, tránh N+1.
     * Dùng trong getCompanyBySlug.
     */
    @Query("""
        SELECT DISTINCT c FROM Company c
        JOIN FETCH c.owner o
        LEFT JOIN FETCH o.roles
        WHERE c.slug = :slug
    """)
    Optional<Company> findBySlugWithOwner(@Param("slug") String slug);

    /**
     * Tìm company theo ownerId — dùng cho getMyCompany.
     * KHÔNG JOIN FETCH owner vì thông tin owner đã có sẵn trong
     * CustomUserDetails (SecurityContext), không cần query User.
     */
    @Query("SELECT c FROM Company c WHERE c.owner.id = :ownerId")
    Optional<Company> findByOwnerId(@Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Company c SET c.followerCount = c.followerCount + 1 WHERE c.id = :companyId")
    void incrementFollowerCount(@Param("companyId") Long companyId);

    @Modifying
    @Query("UPDATE Company c SET c.followerCount = GREATEST(c.followerCount - 1, 0) WHERE c.id = :companyId")
    void decrementFollowerCount(@Param("companyId") Long companyId);
}
