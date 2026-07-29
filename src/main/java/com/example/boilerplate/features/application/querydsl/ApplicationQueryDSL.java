package com.example.boilerplate.features.application.querydsl;

import com.example.boilerplate.common.constant.ApplicationStatus;
import com.example.boilerplate.features.application.entity.Application;
import com.example.boilerplate.features.application.entity.QApplication;
import com.example.boilerplate.features.employee.entity.QEmployee;
import com.example.boilerplate.features.user.entity.QUser;
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
public class ApplicationQueryDSL {

    private final JPAQueryFactory queryFactory;

    /**
     * Lấy danh sách application của một job (dành cho COMPANY xem ứng viên).
     * <p>
     * JOIN FETCH employee + user để tránh N+1 khi map DTO.
     * Có thể lọc theo trạng thái application (PENDING, REVIEWING, ...).
     *
     * @param jobId    ID của job
     * @param status   Lọc theo trạng thái (optional, null = tất cả)
     * @param pageable Thông tin phân trang
     * @return Page {@link Application} đã fetch sẵn employee + user
     */
    public Page<Application> findApplicationsByJob(Long jobId, ApplicationStatus status, Pageable pageable) {
        QApplication application = QApplication.application;
        QEmployee employee = QEmployee.employee;
        QUser user = QUser.user;

        // ===== Predicate: filter theo jobId + status (optional) =====
        BooleanBuilder predicate = new BooleanBuilder();

        // WHERE Job.id = jobId
        predicate.and(application.job.id.eq(jobId));

        // WHERE Application.status = status
        if (status != null) {
            predicate.and(application.status.eq(status));
        }

        // ===== Đếm tổng số bản ghi =====
        // SELECT COUNT(*) FROM Application
        // WHERE Application.Job.Id = jobId
        // AND Application.Status = statuss
        Long total = queryFactory
                .select(application.count())
                .from(application)
                .where(predicate)
                .fetchOne();

        // ===== Xây dựng OrderSpecifier từ Pageable.sort =====
        List<OrderSpecifier<?>> orderSpecifiers = pageable.getSort().stream()
                .map(order -> order.isAscending()
                        ? application.appliedAt.asc()
                        : application.appliedAt.desc())
                .collect(Collectors.toUnmodifiableList());

        // ===== Lấy danh sách (fetch join employee + user để tránh N+1) =====
        List<Application> content = queryFactory
                .selectFrom(application)
                .leftJoin(application.employee, employee).fetchJoin()
                .leftJoin(employee.user, user).fetchJoin()
                .where(predicate)
                .orderBy(orderSpecifiers.toArray(new OrderSpecifier[0]))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
