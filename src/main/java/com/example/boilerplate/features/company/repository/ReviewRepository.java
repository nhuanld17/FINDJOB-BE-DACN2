package com.example.boilerplate.features.company.repository;

import com.example.boilerplate.features.company.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** Kiểm tra employee đã review company này chưa */
    boolean existsByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    /** Lấy review của employee cho 1 company (dùng để kiểm tra ownership) */
    Optional<Review> findByIdAndEmployeeId(Long id, Long employeeId);

    /** Danh sách review của company (phân trang, mới nhất trước) — JOIN FETCH employee + user tránh N+1 */
    @Query("""
            SELECT r FROM Review r
            JOIN FETCH r.employee e
            JOIN FETCH e.user
            WHERE r.company.id = :companyId
            ORDER BY r.createdAt DESC
            """)
    Page<Review> findByCompanyIdWithEmployee(@Param("companyId") Long companyId, Pageable pageable);

    /** Phân bố rating: đếm số lượng review theo từng sao */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.company.id = :companyId GROUP BY r.rating")
    List<Object[]> countByRatingGroupByCompanyId(@Param("companyId") Long companyId);

    /** Điểm trung bình */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.company.id = :companyId")
    Double averageRatingByCompanyId(@Param("companyId") Long companyId);

    /** Tổng số review */
    long countByCompanyId(Long companyId);
}
