package com.example.boilerplate.features.company.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.dto.request.CreateReviewRequest;
import com.example.boilerplate.features.company.dto.request.UpdateReviewRequest;
import com.example.boilerplate.features.company.dto.response.CompanyRatingResponse;
import com.example.boilerplate.features.company.dto.response.ReviewResponse;
import com.example.boilerplate.features.company.service.ReviewService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * API cho chức năng đánh giá công ty (Review).
 * 
 * - USER: tạo/sửa/xoá review của chính mình
 * - Public: xem rating tổng quan + danh sách review
 */
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    // ==================== CRUD (USER) ====================

    /**
     * Tạo đánh giá mới cho công ty.
     * Mỗi employee chỉ review 1 lần / 1 công ty.
     */
    @PostMapping("/api/v1/companies/{companyId}/reviews")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<ReviewResponse>> createReview(
            @PathVariable Long companyId,
            @Valid @RequestBody CreateReviewRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ReviewResponse response = reviewService.createReview(userDetails.getId(), companyId, request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Cập nhật đánh giá của chính mình.
     */
    @PutMapping("/api/v1/reviews/{reviewId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<ReviewResponse>> updateReview(
            @PathVariable Long reviewId,
            @Valid @RequestBody UpdateReviewRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        ReviewResponse response = reviewService.updateReview(reviewId, userDetails.getId(), request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Xoá đánh giá của chính mình.
     */
    @DeleteMapping("/api/v1/reviews/{reviewId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> deleteReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        reviewService.deleteReview(reviewId, userDetails.getId());
        return ResponseEntity.ok(APIResponse.success());
    }

    // ==================== Public read ====================

    /**
     * Lấy tổng quan rating của công ty (điểm TB + phân bố sao).
     * Public — không cần đăng nhập.
     */
    @GetMapping("/api/v1/companies/{companyId}/ratings")
    public ResponseEntity<APIResponse<CompanyRatingResponse>> getCompanyRating(
            @PathVariable Long companyId
    ) {
        CompanyRatingResponse response = reviewService.getCompanyRating(companyId);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Lấy danh sách review của công ty (phân trang, mới nhất trước).
     * Public — không cần đăng nhập.
     */
    @GetMapping("/api/v1/companies/{companyId}/reviews")
    public ResponseEntity<APIResponse<PaginatedResult<ReviewResponse>>> getCompanyReviews(
            @PathVariable Long companyId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        PaginatedResult<ReviewResponse> response = reviewService.getCompanyReviews(companyId, page, size);
        return ResponseEntity.ok(APIResponse.success(response));
    }
}
