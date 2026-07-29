package com.example.boilerplate.features.company.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.dto.request.CreateReviewRequest;
import com.example.boilerplate.features.company.dto.request.UpdateReviewRequest;
import com.example.boilerplate.features.company.dto.response.CompanyRatingResponse;
import com.example.boilerplate.features.company.dto.response.ReviewResponse;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.entity.Review;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.company.repository.ReviewRepository;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service xử lý đánh giá công ty (Review).
 * <p>
 * Chỉ Employee (USER) mới được tạo/sửa/xoá review.
 * Mỗi employee chỉ review 1 lần cho 1 công ty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;

    // ==================== CREATE ====================

    /**
     * Tạo đánh giá mới cho công ty.
     * Chỉ employee đã apply vào công ty này — hoặc bất kỳ employee nào?
     * Hiện tại cho phép bất kỳ employee nào cũng có thể review (mở rộng sau).
     *
     * @param userId   ID của user hiện tại (từ SecurityContext)
     * @param companyId ID của công ty cần đánh giá
     * @param request  Nội dung đánh giá
     * @return ReviewResponse
     */
    @Transactional
    public ReviewResponse createReview(Long userId, Long companyId, CreateReviewRequest request) {
        Employee employee = getEmployeeOrThrow(userId);
        Company company = findCompanyOrThrow(companyId);

        // Chỉ cho review 1 lần
        if (reviewRepository.existsByCompanyIdAndEmployeeId(companyId, employee.getId())) {
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        Review review = new Review();
        review.setCompany(company);
        review.setEmployee(employee);
        review.setRating(request.rating());
        review.setTitle(request.title());
        review.setContent(request.content());
        review.setPros(request.pros());
        review.setCons(request.cons());
        reviewRepository.save(review);

        // Cập nhật cache rating trên Company
        updateCompanyRatingCache(company);

        log.info("Employee {} created review for company {} (rating={})",
                employee.getId(), companyId, request.rating());

        return toResponse(review);
    }

    // ==================== UPDATE ====================

    /**
     * Cập nhật đánh giá của chính mình.
     *
     * @param reviewId ID của review
     * @param userId   ID của user hiện tại
     * @param request  Nội dung cập nhật (chỉ gửi field muốn thay đổi)
     * @return ReviewResponse
     */
    @Transactional
    public ReviewResponse updateReview(Long reviewId, Long userId, UpdateReviewRequest request) {
        Employee employee = getEmployeeOrThrow(userId);
        Review review = reviewRepository.findByIdAndEmployeeId(reviewId, employee.getId())
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        if (request.rating() != null) review.setRating(request.rating());
        if (request.title() != null) review.setTitle(request.title());
        if (request.content() != null) review.setContent(request.content());
        if (request.pros() != null) review.setPros(request.pros());
        if (request.cons() != null) review.setCons(request.cons());
        reviewRepository.save(review);

        // Cập nhật cache rating
        updateCompanyRatingCache(review.getCompany());

        log.info("Employee {} updated review {} for company {}",
                employee.getId(), reviewId, review.getCompany().getId());

        return toResponse(review);
    }

    // ==================== DELETE ====================

    /**
     * Xoá đánh giá của chính mình.
     *
     * @param reviewId ID của review
     * @param userId   ID của user hiện tại
     */
    @Transactional
    public void deleteReview(Long reviewId, Long userId) {
        Employee employee = getEmployeeOrThrow(userId);
        Review review = reviewRepository.findByIdAndEmployeeId(reviewId, employee.getId())
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));

        Company company = review.getCompany();
        reviewRepository.delete(review);

        // Cập nhật cache rating
        updateCompanyRatingCache(company);

        log.info("Employee {} deleted review {} for company {}",
                employee.getId(), reviewId, company.getId());
    }

    // ==================== READ (public) ====================

    /**
     * Lấy tổng quan rating của công ty (điểm TB + phân bố sao).
     * Public — ai cũng xem được.
     */
    @Transactional(readOnly = true)
    public CompanyRatingResponse getCompanyRating(Long companyId) {
        findCompanyOrThrow(companyId);

        Double avg = reviewRepository.averageRatingByCompanyId(companyId);
        long total = reviewRepository.countByCompanyId(companyId);

        // Phân bố rating: { 1: 0, 2: 5, 3: 10, 4: 25, 5: 40 }
        Map<Integer, Long> distribution = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) distribution.put(i, 0L);
        reviewRepository.countByRatingGroupByCompanyId(companyId)
                .forEach(row -> {
                    Integer rating = ((Number) row[0]).intValue();
                    Long count = (Long) row[1];
                    distribution.put(rating, count);
                });

        double finalAvg = (avg != null && !Double.isNaN(avg)) ? Math.round(avg * 10.0) / 10.0 : 0.0;

        return new CompanyRatingResponse(
                finalAvg,
                (int) total,
                distribution
        );
    }

    /**
     * Lấy danh sách review của công ty (phân trang, mới nhất trước).
     * Public — ai cũng xem được.
     */
    @Transactional(readOnly = true)
    public PaginatedResult<ReviewResponse> getCompanyReviews(Long companyId, int page, int size) {
        findCompanyOrThrow(companyId);

        Pageable pageable = PageRequest.of(page, size);
        Page<Review> reviewPage = reviewRepository.findByCompanyIdWithEmployee(companyId, pageable);

        return PaginatedResult.fromPage(reviewPage, this::toResponse);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Tính toán và cập nhật cache averageRating + totalReviews trên Company entity.
     * Gọi sau mỗi lần tạo/sửa/xoá review.
     */
    private void updateCompanyRatingCache(Company company) {
        Double avg = reviewRepository.averageRatingByCompanyId(company.getId());
        long total = reviewRepository.countByCompanyId(company.getId());

        company.setAverageRating(avg != null && !Double.isNaN(avg) ? Math.round(avg * 10.0) / 10.0 : 0.0);
        company.setTotalReviews((int) total);
        companyRepository.save(company);
    }

    private ReviewResponse toResponse(Review review) {
        Employee employee = review.getEmployee();

        return new ReviewResponse(
                review.getId(),
                employee.getId(),
                employee.getUser().getFullName(),
                employee.getUser().getAvatarUrl(),
                review.getRating(),
                review.getTitle(),
                review.getContent(),
                review.getPros(),
                review.getCons(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
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
