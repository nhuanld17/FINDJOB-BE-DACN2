package com.example.boilerplate.features.company.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Request cập nhật đánh giá công ty.
 * Tất cả các field đều optional — chỉ gửi field muốn thay đổi.
 */
public record UpdateReviewRequest(
        @Min(1) @Max(5)
        Integer rating,

        @Size(max = 255)
        String title,

        @Size(min = 10, max = 5000)
        String content,

        @Size(max = 2000)
        String pros,

        @Size(max = 2000)
        String cons
) {
}
