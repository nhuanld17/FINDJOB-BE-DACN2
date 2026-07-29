package com.example.boilerplate.features.employee.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record EducationDto(
        @Size(max = 255, message = "OUT_OF_SIZE")
        String school,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String degree,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String major,

        String description,

        LocalDate startDate,

        LocalDate endDate,

        Boolean isCurrent
) {
}
