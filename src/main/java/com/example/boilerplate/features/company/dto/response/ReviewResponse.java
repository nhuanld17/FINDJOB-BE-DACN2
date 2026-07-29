package com.example.boilerplate.features.company.dto.response;

import java.time.Instant;

/**
 * DTO cho một đánh giá công ty.
 *
 * @param id           ID của review
 * @param employeeId   ID của employee
 * @param employeeName Tên employee
 * @param avatarUrl    Avatar của employee
 * @param rating       Số sao (1-5)
 * @param title        Tiêu đề đánh giá
 * @param content      Nội dung
 * @param pros         Ưu điểm
 * @param cons         Nhược điểm
 * @param createdAt    Thời gian tạo
 * @param updatedAt    Thời gian cập nhật
 */
public record ReviewResponse(
        Long id,
        Long employeeId,
        String employeeName,
        String avatarUrl,
        int rating,
        String title,
        String content,
        String pros,
        String cons,
        Instant createdAt,
        Instant updatedAt
) {
}
