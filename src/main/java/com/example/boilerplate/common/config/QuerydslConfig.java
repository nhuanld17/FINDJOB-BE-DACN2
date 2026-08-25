package com.example.boilerplate.common.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QuerydslConfig — đăng ký bean {@link JPAQueryFactory} cho toàn bộ ứng dụng.
 *
 * Querydsl là gì? Tại sao cần nó?
 *  - Spring Data JPA (repository) chỉ mạnh với query đơn giản, tĩnh
 *    (findByX, derived queries...). Khi query động — điều kiện lọc thay đổi theo
 *    request (search, filter, sort, phân trang...) — viết bằng JPQL/String rất khó
 *    và dễ sai.
 *  - Querydsl giải quyết bằng cách sinh ra Q-classes (ví dụ {@code QJob},
 *    {@code QCompany}) từ entity — query viết kiểu type-safe (an toàn kiểu dữ liệu,
 *    sai field là lỗi ngay lúc compile, không đợi runtime).
 *
 * JPAQueryFactory là gì?
 *  - Là "entry point" để bắt đầu tạo query: {@code queryFactory.selectFrom(qJob)....}
 *  - Cần {@link EntityManager} (bean Spring có sẵn) để thực thi query.
 *  - Khi được khai báo là {@code @Bean}, mọi nơi trong dự án chỉ cần
 *    {@code @Autowired / constructor-inject JPAQueryFactory} là dùng được —
 *    không phải tự new ở từng service.
 *
 * Trong dự án này dùng ở đâu?
 *  - 5 class trong các package {@code ...features.<domain>.querydsl}:
 *    {@code JobQueryDSL}, {@code CompanyQueryDSL}, {@code ApplicationQueryDSL},
 *    {@code EmployeeQueryDSL}, {@code FollowQueryDSL} — nơi xử lý tìm kiếm động,
 *    filter, sort, keyset/offset pagination.
 *  - Ví dụ: {@code GET /api/v1/jobs?search=...&status=...&sort=...} — mỗi request
 *    có thể truyền tổ hợp tham số khác nhau → phải build query động bằng Querydsl.
 *
 * Lưu ý về thư viện:
 * Dự án dùng fork OpenFeign ({@code io.github.openfeign.querydsl}) thay cho gốc
 * {@code com.querydsl} (đã ngừng phát triển ở 5.1.0 và dính CVE-2024-49203 —
 * SQL injection qua orderBy). Fork giữ nguyên package {@code com.querydsl.*} nên
 * code không cần đổi gì, chỉ cần dependency mới (xem pom.xml).
 */
@Configuration
public class QuerydslConfig {

    /**
     * Tạo bean {@link JPAQueryFactory} dùng chung toàn app.
     *
     * Spring sẽ tự inject {@link EntityManager} (quản lý bởi Hibernate/JPA) vào
     * tham số method — đây là dependency injection qua constructor/method param,
     * không cần tự new EntityManager.
     *
     * @param entityManager EntityManager của JPA (Spring cung cấp sẵn)
     * @return JPAQueryFactory dùng để build query động kiểu type-safe
     */
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
