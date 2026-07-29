package com.example.boilerplate.features.employee.querydsl;

import com.example.boilerplate.features.employee.entity.FollowedCompany;
import com.example.boilerplate.features.employee.entity.QFollowedCompany;
import com.example.boilerplate.features.company.entity.QCompany;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FollowQueryDSL {

    private final JPAQueryFactory queryFactory;

    /**
     * Lấy danh sách company mà employee đang follow (phân trang, fetch join company).
     * Mặc định sort theo followedAt giảm dần (mới nhất trước).
     *
     * @param employeeId ID của employee
     * @param pageable   Thông tin phân trang (page, size)
     * @return Page {@link FollowedCompany} chứa danh sách follow + company đã được fetch join
     */
    public Page<FollowedCompany> findFollowedCompaniesWithCompany(Long employeeId, Pageable pageable) {
        QFollowedCompany followedCompany = QFollowedCompany.followedCompany;
        QCompany company = QCompany.company;

        // ===== Predicate: filter theo employeeId =====
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(followedCompany.id.employeeId.eq(employeeId));

        // ===== Đếm tổng số bản ghi =====
        Long total = queryFactory
                .select(followedCompany.count())
                .from(followedCompany)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = pageable.getSort().stream()
                .map(order -> order.isAscending()
                        ? followedCompany.followedAt.asc()
                        : followedCompany.followedAt.desc())
                .collect(Collectors.toUnmodifiableList());

        // ===== Lấy danh sách (fetch join company để tránh N+1) =====
        List<FollowedCompany> content = queryFactory
                .selectFrom(followedCompany)
                .leftJoin(followedCompany.company, company).fetchJoin()
                .where(predicate)
                .orderBy(orderSpecifiers.toArray(new OrderSpecifier[0]))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
