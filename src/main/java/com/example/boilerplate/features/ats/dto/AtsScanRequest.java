package com.example.boilerplate.features.ats.dto;

import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * Request parameters for POST /api/v1/ats/scan.
 * <p>
 * Either {@code jobId} or {@code jdText} must be provided (validated in service).
 * {@code file} is the CV file (PDF/DOCX), max 10 MB (configured in application.yml).
 */
public record AtsScanRequest(
        MultipartFile file,

        Long jobId,

        @Size(max = 3000, message = "jdText must not exceed 3000 characters")
        String jdText
) {}