package com.example.boilerplate.features.company.dto.response;

import java.util.Map;

/**
 * Tổng quan đánh giá của một công ty — dùng cho phần header Company page.
 *
 * @param averageRating     Điểm trung bình (VD: 4.2)
 * @param totalReviews      Tổng số lượt đánh giá
 * @param ratingDistribution Phân bố số sao: { "1": 5, "2": 3, "3": 10, "4": 25, "5": 40 }
 */
public record CompanyRatingResponse(
        double averageRating,
        int totalReviews,
        Map<Integer, Long> ratingDistribution
) {
}
