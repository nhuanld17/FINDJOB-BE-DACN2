package com.example.boilerplate.features.company.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request tạo đánh giá cho công ty.
 *
 * @param rating       Số sao (1-5), bắt buộc
 * @param title        Tiêu đề đánh giá (optional, tối đa 255 ký tự)
 * @param content      Nội dung đánh giá, bắt buộc
 * @param pros         Ưu điểm (optional)
 * @param cons         Nhược điểm (optional)
 */
public record CreateReviewRequest(
        @Min(1) @Max(5)
        int rating,

        @Size(max = 255)
        String title,

        @NotBlank
        @Size(min = 10, max = 5000)
        String content,

        @Size(max = 2000)
        String pros,
        @Size(max = 2000)
        String cons
) {
}
