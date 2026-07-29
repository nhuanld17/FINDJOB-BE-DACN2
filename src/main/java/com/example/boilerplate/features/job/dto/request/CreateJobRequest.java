package com.example.boilerplate.features.job.dto.request;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateJobRequest(

        @NotBlank(message = "BLANK_FIELD")
        @Size(max = 255, message = "OUT_OF_SIZE")
        String title,

        @NotBlank(message = "BLANK_FIELD")
        String description,

        @NotBlank(message = "BLANK_FIELD")
        String requirements,

        String benefits,

        BigDecimal salaryMin,
        BigDecimal salaryMax,

        String salaryCurrency,

        @Min(value = 0, message = "BLANK_FIELD")
        Integer yearsOfExperience,

        @NotNull(message = "BLANK_FIELD")
        Seniority seniority,

        @NotNull(message = "BLANK_FIELD")
        JobType jobType,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String location,

        @NotNull(message = "BLANK_FIELD")
        City city,

        List<String> skillsRequired,

        @NotNull(message = "BLANK_FIELD")
        LocalDate expiryDate,

        List<Long> categoryIds
) {
}
