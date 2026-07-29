package com.example.boilerplate.features.job.dto.request;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record UpdateJobRequest(

        @Size(max = 255, message = "OUT_OF_SIZE")
        String title,

        String description,
        String requirements,
        String benefits,

        BigDecimal salaryMin,
        BigDecimal salaryMax,

        String salaryCurrency,

        @Min(value = 0, message = "BLANK_FIELD")
        Integer yearsOfExperience,

        Seniority seniority,
        JobType jobType,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String location,

        City city,

        List<String> skillsRequired,

        LocalDate expiryDate,

        List<Long> categoryIds
) {
}
