package com.example.boilerplate.features.employee.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ExperienceDto(
        @Size(max = 255, message = "OUT_OF_SIZE")
        String company,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String position,

        String description,

        LocalDate startDate,

        LocalDate endDate,

        Boolean isCurrent
) {
}
