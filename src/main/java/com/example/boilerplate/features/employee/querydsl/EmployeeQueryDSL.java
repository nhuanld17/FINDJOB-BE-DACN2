package com.example.boilerplate.features.employee.querydsl;

import com.example.boilerplate.features.employee.dto.response.CandidateSummaryResponse;
import com.example.boilerplate.features.employee.entity.QEmployee;
import com.example.boilerplate.features.user.entity.QUser;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringTemplate;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * QueryDSL cho Employee — tìm kiếm ứng viên (dành cho COMPANY).
 * 
 * Pattern giống CompanyQueryDSL: BooleanBuilder + Projections.constructor
 * + PageImpl. Chỉ trả hồ sơ CÔNG KHAI (isPublic = true) và user chưa bị ban.
 */
@Component
@RequiredArgsConstructor
public class EmployeeQueryDSL {

    private final JPAQueryFactory queryFactory;

    /**
     * Tìm kiếm ứng viên với phân trang + bộ lọc.
     *
     * @param search       Từ khoá: tên / chức danh / kỹ năng (containsIgnoreCase)
     * @param skills       Danh sách kỹ năng cách nhau dấu phẩy — match nếu chứa TẤT CẢ
     * @param city         Lọc theo thành phố (enum name, vd HA_NOI)
     * @param isOpenToWork Lọc người đang sẵn sàng làm việc (null = bỏ qua)
     * @param pageable     Thông tin phân trang (page, size, sort)
     * @return Page {@link CandidateSummaryResponse}
     */
    public Page<CandidateSummaryResponse> searchCandidates(
            String search,
            String skills,
            String city,
            Boolean isOpenToWork,
            Pageable pageable
    ) {
        QEmployee employee = QEmployee.employee;
        QUser user = QUser.user;

        // skills lưu dạng jsonb → cast sang text để dùng LIKE.
        // Tìm theo token có dấu nháy ("Java") tránh false-positive:
        //   '"Java"' khớp "Java" nhưng KHÔNG khớp "JavaScript"
        StringTemplate skillsText = Expressions.stringTemplate(
                "cast({0} as text)", employee.skills
        );

        BooleanBuilder predicate = new BooleanBuilder();
        // Chỉ hồ sơ CÔNG KHAI — tôn trọng quyền riêng tư của ứng viên
        predicate.and(employee.isPublic.isTrue());
        // Loại user đã bị xoá mềm (ban)
        predicate.and(user.deleted.isFalse());

        if (StringUtils.hasText(search)) {
            String keyword = search.trim();
            predicate.and(
                    user.fullName.containsIgnoreCase(keyword)
                            .or(employee.title.containsIgnoreCase(keyword))
                            .or(skillsText.containsIgnoreCase("\"" + keyword + "\""))
            );
        }

        if (StringUtils.hasText(skills)) {
            // skills="Java,Spring Boot" → phải chứa TẤT CẢ (AND)
            for (String skill : skills.split(",")) {
                if (StringUtils.hasText(skill)) {
                    predicate.and(skillsText.containsIgnoreCase("\"" + skill.trim() + "\""));
                }
            }
        }

        if (StringUtils.hasText(city)) {
            predicate.and(employee.city.equalsIgnoreCase(city.trim()));
        }

        if (isOpenToWork != null) {
            predicate.and(employee.isOpenToWork.eq(isOpenToWork));
        }

        // ===== Đếm tổng số bản ghi =====
        Long total = queryFactory
                .select(employee.count())
                .from(employee)
                .join(employee.user, user)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = pageable.getSort().stream()
                .map(order -> {
                    Order direction = order.isAscending() ? Order.ASC : Order.DESC;
                    return switch (order.getProperty()) {
                        case "title" -> new OrderSpecifier<>(direction, employee.title);
                        case "city" -> new OrderSpecifier<>(direction, employee.city);
                        case "updatedAt" -> new OrderSpecifier<>(direction, employee.updatedAt);
                        case "createdAt" -> new OrderSpecifier<>(direction, employee.createdAt);
                        default -> new OrderSpecifier<>(Order.DESC, employee.updatedAt);
                    };
                }).collect(Collectors.toUnmodifiableList());

        // ===== Lấy danh sách ứng viên =====
        List<CandidateSummaryResponse> content = queryFactory
                .select(Projections.constructor(
                        CandidateSummaryResponse.class,
                        employee.id,
                        user.fullName,
                        user.avatarUrl,
                        employee.title,
                        employee.city,
                        employee.bio,
                        employee.isOpenToWork,
                        employee.skills,
                        employee.updatedAt
                ))
                .from(employee)
                .join(employee.user, user)
                .where(predicate)
                .orderBy(orderSpecifiers.toArray(new OrderSpecifier[0]))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total);
    }
}
