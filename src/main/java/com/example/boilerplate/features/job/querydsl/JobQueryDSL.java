package com.example.boilerplate.features.job.querydsl;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.entity.QJob;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class JobQueryDSL {

    private final JPAQueryFactory queryFactory;

    /**
     * Tìm kiếm job công khai với phân trang, bộ lọc và sắp xếp.
     * <p>
     * Luôn filter: isDeleted = false, status ∈ {ACTIVE, EXPIRED}, company chưa bị xoá.
     * Các filter khác (city, seniority, jobType, search) đều OPTIONAL.
     *
     * @param search     Từ khoá tìm kiếm theo title (không phân biệt hoa/thường)
     * @param city       Lọc theo thành phố
     * @param seniority  Lọc theo cấp bậc (Seniority enum)
     * @param jobType    Lọc theo loại hình công việc (JobType enum)
     * @param salaryMin  Lọc job có mức lương TỐI THIỂU (salaryMax >= salaryMin)
     * @param salaryMax  Lọc job có mức lương TỐI ĐA (salaryMin <= salaryMax)
     * @param pageable   Thông tin phân trang (page, size, sort)
     * @return Page {@link Job} chứa danh sách job thoả mãn điều kiện
     */
    public Page<Job> searchJobs(
            String search,
            City city,
            String seniority,
            String jobType,
            Long salaryMin,
            Long salaryMax,
            Pageable pageable
    ) {
        QJob job = QJob.job;

        // ===== Xây dựng predicate động =====
        BooleanBuilder predicate = new BooleanBuilder();
        // Luôn filter: chưa xoá, ACTIVE hoặc EXPIRED, công ty chưa bị xoá
        predicate.and(job.deleted.isFalse());
        predicate.and(job.status.in(JobStatus.ACTIVE, JobStatus.EXPIRED));
        predicate.and(job.company.deleted.isFalse());

        if (StringUtils.hasText(search)) {
            predicate.and(job.title.containsIgnoreCase(search.trim()));
        }

        if (city != null) {
            predicate.and(job.city.eq(city));
        }

        if (StringUtils.hasText(seniority)) {
            predicate.and(job.seniority.eq(Seniority.valueOf(seniority)));
        }

        if (StringUtils.hasText(jobType)) {
            predicate.and(job.jobType.eq(JobType.valueOf(jobType)));
        }

        // Lọc theo mức lương — khoảng lương của job chạm vào khoảng user chọn.
        // salaryMin != null → job phải có salaryMax >= salaryMin (job trả ít nhất mức đó)
        // salaryMax != null → job phải có salaryMin <= salaryMax (job không vượt mức đó)
        // Job lương null (Thoả thuận) sẽ bị loại khi lọc lương.
        if (salaryMin != null) {
            predicate.and(job.salaryMax.goe(BigDecimal.valueOf(salaryMin)));
        }
        if (salaryMax != null) {
            predicate.and(job.salaryMin.loe(BigDecimal.valueOf(salaryMax)));
        }

        // ===== Đếm tổng số bản ghi =====
        Long total = queryFactory
                .select(job.count())
                .from(job)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = buildOrderSpecifiers(job, pageable);

        // ===== Lấy danh sách job (fetch join company để tránh N+1) =====
        List<Job> content = queryFactory
                .selectFrom(job)
                .leftJoin(job.company).fetchJoin()
                .where(predicate)
                .orderBy(orderSpecifiers.toArray(new OrderSpecifier[0]))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    /**
     * Liệt kê job dành cho COMPANY (chủ sở hữu) — màn hình "Quản lý tin tuyển dụng".
     * <p>
     * KEYSET PAGINATION: thay vì OFFSET, dùng mốc (lastCreatedAt, lastId) để lấy
     * các dòng SAU mốc — ổn định khi dữ liệu đổi giữa chừng, luôn nhanh (index).
     * Sort CỐ ĐỊNH: created_at DESC, id DESC (mới nhất lên đầu).
     * <ul>
     *   <li>statuses rỗng/null → trả TẤT CẢ status (ACTIVE/EXPIRED/DRAFT/CLOSED)</li>
     *   <li>search → tìm theo title (không phân biệt hoa/thường)</li>
     *   <li>lastCreatedAt/lastId null → trang đầu</li>
     *   <li>Trả {@code limit + 1} dòng để service biết còn trang sau hay không</li>
     * </ul>
     *
     * @param companyId     ID công ty (từ JWT — userId → company)
     * @param statuses      Danh sách status cần lọc (rỗng/null = tất cả)
     * @param search        Từ khoá tìm theo title (optional)
     * @param lastCreatedAt Mốc thời gian của item cuối (null = trang đầu)
     * @param lastId        Id của item cuối (null = trang đầu)
     * @param limit         Số item mong muốn (service truyền size)
     * @return List {@link Job} — tối đa {@code limit + 1} dòng
     */
    public List<Job> searchManageJobs(
            Long companyId,
            List<JobStatus> statuses,
            String search,
            Instant lastCreatedAt, Long lastId, int limit) {

        QJob job = QJob.job;

        // ====== Xây dựng predicate động =======
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(job.company.id.eq(companyId));
        predicate.and(job.deleted.isFalse());

        // Filter status: rỗng/null -> lấy tất cả, có giá trị thực -> lọc theo các status
        if (statuses != null && !statuses.isEmpty()) {
            predicate.and(job.status.in(statuses));
        }

        if (StringUtils.hasText(search)) {
            predicate.and(job.title.containsIgnoreCase(search.trim()));
        }

        /**
         * KEYSET: Chỉ lấy các dòng phía sau MỐC (theo sort created_at DESC, id DESC)
         *
         * (created_at, id) < (lastCreatedAt, lastId)
         * -> created_at nhỏ hơn mốc, hoặc cùng created_at mà id nhỏ hơn
         */
        if (lastCreatedAt != null && lastId != null) {
            predicate.and(job.createdAt.lt(lastCreatedAt)
                    .or(job.createdAt.eq(lastCreatedAt).and(job.id.lt(lastId))));
        }

        // ===== Lấy limit + 1 dòng (lấy dư 1 để service biết còn hasMore) ======
        return queryFactory
                .selectFrom(job)
                .leftJoin(job.company).fetchJoin()
                .where(predicate)
                .orderBy(job.createdAt.desc(), job.id.desc())
                .limit(limit + 1L)
                .fetch();
    }

    /**
     * Chuyển {@link Pageable#getSort()} thành danh sách {@link OrderSpecifier} cho query.
     * <p>
     * Chỉ chấp nhận các field WHITELIST (an toàn, tránh SQL injection qua sort);
     * field không nằm trong whitelist → mặc định sắp theo createdAt giảm dần.
     *
     * @param job      QJob để truy cập các cột (title, createdAt, ...)
     * @param pageable Phân trang chứa sort
     * @return Danh sách OrderSpecifier (có thể rỗng nếu sort rỗng)
     */
    private List<OrderSpecifier<?>> buildOrderSpecifiers(QJob job, Pageable pageable) {
        return pageable.getSort().stream()
                .map(order -> {
                    Order direction = order.isAscending() ? Order.ASC : Order.DESC;
                    return switch (order.getProperty()) {
                        case "title" -> new OrderSpecifier<>(direction, job.title);
                        case "createdAt" -> new OrderSpecifier<>(direction, job.createdAt);
                        case "updatedAt" -> new OrderSpecifier<>(direction, job.updatedAt);
                        case "salaryMin" -> new OrderSpecifier<>(direction, job.salaryMin);
                        case "salaryMax" -> new OrderSpecifier<>(direction, job.salaryMax);
                        case "expiryDate" -> new OrderSpecifier<>(direction, job.expiryDate);
                        case "city" -> new OrderSpecifier<>(direction, job.city);
                        case "seniority" -> new OrderSpecifier<>(direction, job.seniority);
                        case "jobType" -> new OrderSpecifier<>(direction, job.jobType);
                        default -> new OrderSpecifier<>(Order.DESC, job.createdAt);
                    };
                }).collect(Collectors.toUnmodifiableList());
    }
}
