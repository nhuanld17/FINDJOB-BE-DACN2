package com.example.boilerplate.features.ats.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.features.ats.dto.AtsResultDto;
import com.example.boilerplate.features.ats.service.AtsScoringService;
import com.example.boilerplate.features.ats.service.FileParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller cho ATS Resume Scoring (chỉ role USER).
 * <p>
 * Endpoint: POST /api/v1/ats/scan
 * <p>
 * Cho phép USER upload CV PDF/DOCX, kèm jobId (lấy JD từ DB) hoặc
 * jdText (tự paste) để AI chấm độ khớp.
 */
@RestController
@RequestMapping("/api/v1/ats")
@RequiredArgsConstructor
public class AtsController {

    private final FileParserService fileParserService;
    private final AtsScoringService atsScoringService;

    @PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<AtsResultDto>> scanCv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "jobId", required = false) Long jobId,
            @RequestParam(value = "jdText", required = false) String jdText
    ) {
        // 1. Extract text từ file CV
        String cvText = fileParserService.extractText(file);

        // 2. Gọi AI scoring
        AtsResultDto result = atsScoringService.score(cvText, jobId, jdText);

        return ResponseEntity.ok(APIResponse.success(result));
    }
}