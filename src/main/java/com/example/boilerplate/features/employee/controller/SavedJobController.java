package com.example.boilerplate.features.employee.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.employee.dto.response.SavedJobResponse;
import com.example.boilerplate.features.employee.service.SavedJobService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/saved-jobs")
@RequiredArgsConstructor
public class SavedJobController {

    private final SavedJobService savedJobService;

    /** Lưu một job để xem sau */
    @PostMapping("/jobs/{jobId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> saveJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        savedJobService.saveJob(userDetails.getId(), jobId);
        return ResponseEntity.ok(APIResponse.success());
    }

    /** Bỏ lưu một job */
    @DeleteMapping("/jobs/{jobId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> unsaveJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        savedJobService.unsaveJob(userDetails.getId(), jobId);
        return ResponseEntity.ok(APIResponse.success());
    }

    /** Lấy danh sách job đã lưu */
    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<PaginatedResult<SavedJobResponse>>> getSavedJobs(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        PaginatedResult<SavedJobResponse> response = savedJobService.getSavedJobs(
                userDetails.getId(), page, size
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Kiểm tra job này đã lưu chưa + đếm số lượt lưu */
    @GetMapping("/jobs/{jobId}/status")
    public ResponseEntity<APIResponse<Map<String, Object>>> getSaveStatus(
            @PathVariable Long jobId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        boolean isSaved = userDetails != null
                && userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER"))
                && savedJobService.isSaved(userDetails.getId(), jobId);

        return ResponseEntity.ok(APIResponse.success(Map.of(
                "isSaved", isSaved
        )));
    }
}
