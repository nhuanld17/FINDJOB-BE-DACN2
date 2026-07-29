package com.example.boilerplate.features.employee.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.features.employee.dto.request.CertificateRequest;
import com.example.boilerplate.features.employee.dto.request.EducationDto;
import com.example.boilerplate.features.employee.dto.request.ExperienceDto;
import com.example.boilerplate.features.employee.dto.request.UpdateEmployeeRequest;
import com.example.boilerplate.features.employee.dto.response.EmployeeResponse;
import com.example.boilerplate.features.employee.service.EmployeeService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /**
     * Xem public profile của Employee theo employeeId.
     * Public — ai cũng xem được (kể cả COMPANY).
     * Nếu employee set isPublic = false hoặc user bị ban → 404.
     */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<EmployeeResponse>> getPublicProfile(
            @PathVariable Long id
    ) {
        EmployeeResponse response = employeeService.getPublicProfile(id);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> getMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.getMyProfile(userDetails.getId());
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Cập nhật thông tin cơ bản của Employee profile.
     * Skills / Experiences / Education / Certificates có API riêng.
     */
    @PutMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> updateProfile(
            @Valid @RequestBody UpdateEmployeeRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.updateProfile(userDetails.getId(), request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Upload avatar cho employee.
     * Nhận multipart file, tự động xoá avatar cũ trên Cloudinary.
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.uploadAvatar(userDetails.getId(), file);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Cập nhật danh sách kỹ năng (ghi đè toàn bộ).
     * Nhận JSON array: ["Java", "Spring Boot", "PostgreSQL", ...]
     */
    @PutMapping("/me/skills")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> updateSkills(
            @RequestBody List<String> skills,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.updateSkills(userDetails.getId(), skills);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Cập nhật danh sách kinh nghiệm làm việc (ghi đè toàn bộ).
     */
    @PutMapping("/me/experiences")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> updateExperiences(
            @Valid @RequestBody List<ExperienceDto> experiences,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.updateExperiences(
                userDetails.getId(), experiences
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Cập nhật danh sách học vấn (ghi đè toàn bộ).
     */
    @PutMapping("/me/education")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse>> updateEducation(
            @Valid @RequestBody List<EducationDto> education,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        EmployeeResponse response = employeeService.updateEducation(
                userDetails.getId(), education
        );
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /**
     * Lấy danh sách chứng chỉ.
     */
    @GetMapping("/me/certificates")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<List<EmployeeResponse.CertificateResponse>>> getCertificates(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<EmployeeResponse.CertificateResponse> certs = employeeService.getCertificates(
                userDetails.getId()
        );
        return ResponseEntity.ok(APIResponse.success(certs));
    }

    /** Thêm chứng chỉ mới */
    @PostMapping("/me/certificates")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse.CertificateResponse>> addCertificate(
            @Valid @RequestBody CertificateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        var response = employeeService.addCertificate(userDetails.getId(), request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Cập nhật chứng chỉ */
    @PutMapping("/me/certificates/{certId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<EmployeeResponse.CertificateResponse>> updateCertificate(
            @PathVariable Long certId,
            @Valid @RequestBody CertificateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        var response = employeeService.updateCertificate(userDetails.getId(), certId, request);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Xoá chứng chỉ */
    @DeleteMapping("/me/certificates/{certId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> deleteCertificate(
            @PathVariable Long certId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        employeeService.deleteCertificate(userDetails.getId(), certId);
        return ResponseEntity.ok(APIResponse.success());
    }
}
