package com.example.boilerplate.features.company.querydsl;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.features.company.dto.response.CompanySummaryResponse;
import com.example.boilerplate.features.company.entity.QCompany;
import com.example.boilerplate.features.job.entity.QJob;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
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
public class CompanyQueryDSL {

    private final JPAQueryFactory queryFactory;

    /**
     * Tìm kiếm công ty với phân trang, bộ lọc và sắp xếp.
     *
     * @param search   Từ khóa tìm kiếm theo tên công ty (không phân biệt hoa/thường)
     * @param industry Lọc theo ngành nghề
     * @param city     Lọc theo thành phố
     * @param pageable Thông tin phân trang (page, size, sort)
     * @return Page {@link CompanySummaryResponse} chứa danh sách công ty + jobCount
     */
    public Page<CompanySummaryResponse> searchCompanies(
            String search,
            String industry,
            String city,
            Pageable pageable
    ) {
        QCompany company = QCompany.company;
        QJob job = QJob.job;

        BooleanBuilder predicate = new BooleanBuilder();
        // Chỉ lấy công ty chưa bị xóa (soft delete)
        predicate.and(company.deleted.isFalse());

        if (StringUtils.hasText(search)) {
            predicate.and(company.name.containsIgnoreCase(search.trim()));
        }

        if (StringUtils.hasText(industry)) {
            predicate.and(company.industry.containsIgnoreCase(industry.trim()));
        }

        if (city != null) {
            predicate.and(company.city.eq(City.valueOf(city)));
        }

        // ===== Đếm tổng số bản ghi =====
        Long total = queryFactory
                .select(company.count())
                .from(company)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = pageable.getSort().stream()
                .map(order -> {
                    Order direction = order.isAscending() ? Order.ASC : Order.DESC;
                    return switch (order.getProperty()) {
                        case "name" -> new OrderSpecifier<>(direction, company.name);
                        case "createdAt" -> new OrderSpecifier<>(direction, company.createdAt);
                        case "updatedAt" -> new OrderSpecifier<>(direction, company.updatedAt);
                        case "industry" -> new OrderSpecifier<>(direction, company.industry);
                        case "city" -> new OrderSpecifier<>(direction, company.city);
                        default -> new OrderSpecifier<>(Order.DESC, company.createdAt);
                    };
                }).collect(Collectors.toUnmodifiableList());

        // ===== Lấy danh sách công ty =====
        List<CompanySummaryResponse> content = queryFactory
                .select(Projections.constructor(
                        CompanySummaryResponse.class,
                        company.id,
                        company.name,
                        company.slug,
                        company.logoUrl,
                        company.coverUrl,
                        company.industry,
                        company.companySize,
                        company.city,
                        company.website,
                        // Subquery: đếm số lượng job ACTIVE của công ty
                        JPAExpressions
                                .select(job.count().stringValue())
                                .from(job)
                                .where(job.company.eq(company)
                                        .and(job.status.eq(JobStatus.ACTIVE))),
                        company.followerCount
                ))
                .from(company)
                .where(predicate)
                .orderBy(orderSpecifiers.toArray(new OrderSpecifier[0]))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total);
    }
}