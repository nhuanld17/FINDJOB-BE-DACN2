package com.example.boilerplate.features.company.controller;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.dto.request.UpdateCompanyRequest;
import com.example.boilerplate.features.company.dto.response.CompanyResponse;
import com.example.boilerplate.features.company.dto.response.CompanyStatsResponse;
import com.example.boilerplate.features.company.dto.response.CompanySummaryResponse;
import com.example.boilerplate.features.company.service.CompanyService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    // Lấy thông tin của company (hiển thị cho người sở hữu)
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<APIResponse<CompanyResponse>> getMyCompany(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        CompanyResponse response = companyService.getMyCompany(userDetails);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // Tìm công ty bằng id
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<CompanyResponse>> getCompanyById(@PathVariable Long id) {
        CompanyResponse response = companyService.getCompanyById(id);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // tìm công ty theo slug
    @GetMapping("/slug/{slug}")
    public ResponseEntity<APIResponse<CompanyResponse>> getCompanyBySlug(@PathVariable String slug) {
        CompanyResponse response = companyService.getCompanyBySlug(slug);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // Update thông tin cho công ty
    @PutMapping("/{companyId}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<CompanyResponse>> updateCompany(
            @PathVariable Long companyId,
            @RequestBody @Valid UpdateCompanyRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        CompanyResponse response = companyService.updateCompany(companyId, userDetails.getId(), request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // Upload logo cho công ty
    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<CompanyResponse>> uploadLogo(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        CompanyResponse response = companyService.uploadLogo(id, userDetails.getId(), file);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // Upload ảnh bìa cho công ty
    @PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<CompanyResponse>> uploadCover(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        CompanyResponse response = companyService.uploadCover(id, userDetails.getId(), file);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    // Xóa công ty theo id
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<Void>> deleteCompany(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        companyService.deleteCompany(id, userDetails.getId());
        return ResponseEntity.ok(APIResponse.success());
    }

    /**
     * Company Dashboard — thống kê tổng quan.
     * Chỉ COMPANY mới xem được.
     */
    @GetMapping("/me/stats")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<APIResponse<CompanyStatsResponse>> getCompanyStats(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        CompanyStatsResponse response = companyService.getCompanyStats(userDetails.getId());
        return ResponseEntity.ok(APIResponse.success(response));
    }

    @GetMapping("")
    public ResponseEntity<APIResponse<PaginatedResult<CompanySummaryResponse>>> getPaginatedCompanies(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "industry", required = false) String industry,
            @RequestParam(name = "city", required = false) City city,
            @RequestParam(name = "sort", defaultValue = "createdAt,desc") String sort
    ) {

        PaginatedResult<CompanySummaryResponse> response = companyService.getPaginatedCompanies(page, size, search, industry, city, sort);

        return  ResponseEntity.ok(APIResponse.success(response));
    }
}
