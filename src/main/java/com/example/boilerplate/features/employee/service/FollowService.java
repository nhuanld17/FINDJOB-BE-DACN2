package com.example.boilerplate.features.employee.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.employee.dto.response.FollowedCompanyResponse;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.entity.FollowedCompany;
import com.example.boilerplate.features.employee.entity.FollowedCompanyId;
import com.example.boilerplate.features.employee.querydsl.FollowQueryDSL;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import com.example.boilerplate.features.employee.repository.FollowedCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service xử lý follow/unfollow company.
 * <p>
 * Chỉ employee (người xin việc) mới có thể follow company.
 * Mỗi employee chỉ follow 1 company tối đa 1 lần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowedCompanyRepository followedCompanyRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final FollowQueryDSL followQueryDSL;

    /**
     * Follow một company.
     *
     * @param userId    ID của user hiện tại
     * @param companyId ID của company cần follow
     */
    @Transactional
    public void follow(Long userId, Long companyId) {
        Employee employee = getEmployeeOrThrow(userId);
        Company company = findCompanyOrThrow(companyId);

        if (followedCompanyRepository.existsByEmployeeIdAndCompanyId(employee.getId(), companyId)) {
            throw new AppException(ErrorCode.ALREADY_FOLLOWING);
        }

        FollowedCompany follow = new FollowedCompany();
        follow.setId(new FollowedCompanyId(employee.getId(), companyId));
        follow.setEmployee(employee);
        follow.setCompany(company);

        followedCompanyRepository.save(follow);

        // Cập nhật follower_count cache trên company
        companyRepository.incrementFollowerCount(companyId);

        log.info("Employee {} followed company {}", employee.getId(), companyId);
    }

    /**
     * Unfollow một company.
     *
     * @param userId    ID của user hiện tại
     * @param companyId ID của company cần unfollow
     */
    @Transactional
    public void unfollow(Long userId, Long companyId) {
        Employee employee = getEmployeeOrThrow(userId);
        Company company = findCompanyOrThrow(companyId);

        if (!followedCompanyRepository.existsByEmployeeIdAndCompanyId(employee.getId(), companyId)) {
            throw new AppException(ErrorCode.NOT_FOLLOWING);
        }

        followedCompanyRepository.deleteByEmployeeIdAndCompanyId(employee.getId(), companyId);

        // Giảm follower_count cache trên company (không xuống dưới 0)
        companyRepository.decrementFollowerCount(companyId);

        log.info("Employee {} unfollowed company {}", employee.getId(), companyId);
    }

    /**
     * Lấy danh sách company mà employee đang follow (phân trang, kèm thông tin cơ bản).
     */
    @Transactional(readOnly = true)
    public PaginatedResult<FollowedCompanyResponse> getFollowedCompanies(Long userId, Pageable pageable) {
        Employee employee = getEmployeeOrThrow(userId);
        Page<FollowedCompany> page = followQueryDSL.findFollowedCompaniesWithCompany(employee.getId(), pageable);
        return PaginatedResult.fromPage(page, this::toFollowedCompanyResponse);
    }

    /**
     * Kiểm tra user hiện tại có đang follow company không.
     */
    @Transactional(readOnly = true)
    public boolean isFollowing(Long userId, Long companyId) {
        Employee employee = getEmployeeOrThrow(userId);
        return followedCompanyRepository.existsByEmployeeIdAndCompanyId(employee.getId(), companyId);
    }

    /**
     * Đếm số lượng follower của một company.
     * Dùng follower_count cache từ entity Company thay vì COUNT(*).
     */
    @Transactional(readOnly = true)
    public long getFollowerCount(Long companyId) {
        Company company = findCompanyOrThrow(companyId);
        return company.getFollowerCount();
    }

    // ===== Private helpers =====

    private FollowedCompanyResponse toFollowedCompanyResponse(FollowedCompany follow) {
        Company company = follow.getCompany();
        return FollowedCompanyResponse.builder()
                .companyId(company.getId())
                .companyName(company.getName())
                .companySlug(company.getSlug())
                .companyLogoUrl(company.getLogoUrl())
                .industry(company.getIndustry())
                .city(company.getCity())
                .followerCount(company.getFollowerCount())
                .build();
    }

    private Employee getEmployeeOrThrow(Long userId) {
        return employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    private Company findCompanyOrThrow(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        return company;
    }
}
