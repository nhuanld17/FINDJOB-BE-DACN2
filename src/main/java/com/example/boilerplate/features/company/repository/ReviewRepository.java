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

    /**
     * Phân bố rating: đếm số lượng review theo từng mức sao.
     *
     * OUTPUT — List<Object[]>, mỗi phần tử là 1 mảng 2 ô ứng với 1 mức sao:
     *   row[0] = rating (Integer, giá trị 1..5) — mức sao
     *   row[1] = count   (Long)                 — số review ở mức sao đó
     *
     * Ví dụ: [[5, 40L], [3, 10L], [1, 2L]] nghĩa là 40 review 5 sao, 10 review 3 sao, 2 review 1 sao.
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.company.id = :companyId GROUP BY r.rating")
    List<Object[]> countByRatingGroupByCompanyId(@Param("companyId") Long companyId);

    /** Điểm trung bình */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.company.id = :companyId")
    Double averageRatingByCompanyId(@Param("companyId") Long companyId);

    /** Tổng số review */
    long countByCompanyId(Long companyId);
}
