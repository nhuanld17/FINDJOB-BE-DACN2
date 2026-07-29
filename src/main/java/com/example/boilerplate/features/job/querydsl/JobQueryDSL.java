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
     * @param search    Từ khoá tìm kiếm theo title (không phân biệt hoa/thường)
     * @param city      Lọc theo thành phố
     * @param seniority Lọc theo cấp bậc (Seniority enum)
     * @param jobType   Lọc theo loại hình công việc (JobType enum)
     * @param pageable  Thông tin phân trang (page, size, sort)
     * @return Page {@link Job} chứa danh sách job thoả mãn điều kiện
     */
    public Page<Job> searchJobs(
            String search,
            City city,
            String seniority,
            String jobType,
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

        // ===== Đếm tổng số bản ghi =====
        Long total = queryFactory
                .select(job.count())
                .from(job)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = pageable.getSort().stream()
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
}
