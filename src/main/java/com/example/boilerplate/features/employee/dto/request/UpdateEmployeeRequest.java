package com.example.boilerplate.features.employee.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateEmployeeRequest(

        @Size(max = 20, message = "OUT_OF_SIZE")
        String phone,

        LocalDate dateOfBirth,

        @Size(max = 10, message = "OUT_OF_SIZE")
        String gender,

        @Size(max = 100, message = "OUT_OF_SIZE")
        String city,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String address,

        @Size(max = 500, message = "OUT_OF_SIZE")
        String cvUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String githubUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String linkedinUrl,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String portfolioUrl,

        Boolean isPublic,

        Boolean isOpenToWork,

        @Size(max = 255, message = "OUT_OF_SIZE")
        String title,

        @Size(max = 5000, message = "OUT_OF_SIZE")
        String bio
) {
}
