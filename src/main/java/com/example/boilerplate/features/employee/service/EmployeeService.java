package com.example.boilerplate.features.employee.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.features.employee.dto.request.CertificateRequest;
import com.example.boilerplate.features.employee.dto.request.EducationDto;
import com.example.boilerplate.features.employee.dto.request.ExperienceDto;
import com.example.boilerplate.features.employee.dto.request.UpdateEmployeeRequest;
import com.example.boilerplate.features.employee.dto.response.EmployeeResponse;
import com.example.boilerplate.features.employee.entity.Certificate;
import com.example.boilerplate.features.employee.entity.Employee;
import com.example.boilerplate.features.employee.repository.CertificateRepository;
import com.example.boilerplate.features.employee.repository.EmployeeRepository;
import com.example.boilerplate.features.user.repository.UserRepository;
import com.example.boilerplate.infrastructure.cloudinary.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final CertificateRepository certificateRepository;
    private final CloudinaryService cloudinaryService;
    private final UserRepository userRepository;

    /**
     * Lấy Employee Profile của user hiện tại (đầy đủ thông tin).
     */
    @Transactional(readOnly = true)
    public EmployeeResponse getMyProfile(Long userId) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());

        return toResponse(employee, certificates);
    }

    /**
     * Lấy public profile của Employee theo employeeId.
     * Chỉ trả về nếu employee tồn tại, chưa bị ban và isPublic = true.
     * Dùng cho COMPANY xem hồ sơ ứng viên.
     */
    @Transactional(readOnly = true)
    public EmployeeResponse getPublicProfile(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Nếu employee set isPublic = false thì không cho xem
        if (Boolean.FALSE.equals(employee.getIsPublic())) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }

        // Nếu user bị ban thì không cho xem
        if (employee.getUser().isDeleted()) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }

        // Lấy danh sách chứng chỉ và bằng cấp
        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());

        return toResponse(employee, certificates);
    }

    /**
     * Cập nhật thông tin cơ bản của Employee profile.
     * Không bao gồm skills, experiences, education, certificates (có API riêng).
     */
    @Transactional
    public EmployeeResponse updateProfile(Long userId, UpdateEmployeeRequest request) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        if (request.phone() != null) employee.setPhone(request.phone());
        if (request.dateOfBirth() != null) employee.setDateOfBirth(request.dateOfBirth());
        if (request.gender() != null) employee.setGender(request.gender());
        if (request.city() != null) employee.setCity(request.city());
        if (request.address() != null) employee.setAddress(request.address());
        if (request.cvUrl() != null) employee.setCvUrl(request.cvUrl());
        if (request.githubUrl() != null) employee.setGithubUrl(request.githubUrl());
        if (request.linkedinUrl() != null) employee.setLinkedinUrl(request.linkedinUrl());
        if (request.portfolioUrl() != null) employee.setPortfolioUrl(request.portfolioUrl());
        if (request.isPublic() != null) employee.setIsPublic(request.isPublic());
        if (request.isOpenToWork() != null) employee.setIsOpenToWork(request.isOpenToWork());
        if (request.title() != null) employee.setTitle(request.title());
        if (request.bio() != null) employee.setBio(request.bio());

        employeeRepository.save(employee);

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());
        return toResponse(employee, certificates);
    }

    /**
     * Cập nhật danh sách skills (ghi đè hoàn toàn).
     */
    @Transactional
    public EmployeeResponse updateSkills(Long userId, List<String> skills) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        employee.setSkills(skills != null ? skills : new ArrayList<>());
        employeeRepository.save(employee);

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());
        return toResponse(employee, certificates);
    }

    /**
     * Cập nhật danh sách experiences (ghi đè hoàn toàn).
     */
    @Transactional
    public EmployeeResponse updateExperiences(Long userId, List<ExperienceDto> experiences) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Validate từng experience
        if (experiences != null) {
            for (ExperienceDto exp : experiences) {
                // Bắt buộc phải có startDate
                if (exp.startDate() == null) {
                    throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                }
                // Nếu là current (đang làm) thì endDate phải null
                if (Boolean.TRUE.equals(exp.isCurrent())) {
                    if (exp.endDate() != null) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                } else {
                    // Không phải current → bắt buộc có endDate
                    if (exp.endDate() == null) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                    // Và endDate >= startDate
                    if (exp.endDate().isBefore(exp.startDate())) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                }
            }
        }

        // Convert DTO → List<Map> để lưu vào JSONB
        List<Map<String, Object>> expMaps = experiences != null
                ? experiences.stream().map(this::experienceToMap).collect(Collectors.toList())
                : new ArrayList<>();

        employee.setExperiences(expMaps);
        employeeRepository.save(employee);

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());
        return toResponse(employee, certificates);
    }

    /**
     * Cập nhật danh sách education (ghi đè hoàn toàn).
     */
    @Transactional
    public EmployeeResponse updateEducation(Long userId, List<EducationDto> education) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        // Validate từng education
        if (education != null) {
            for (EducationDto edu : education) {
                // Bắt buộc phải có startDate
                if (edu.startDate() == null) {
                    throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                }
                // Nếu là current (đang học) thì endDate phải null
                if (Boolean.TRUE.equals(edu.isCurrent())) {
                    if (edu.endDate() != null) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                } else {
                    // Không phải current → bắt buộc có endDate
                    if (edu.endDate() == null) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                    // Và endDate >= startDate
                    if (edu.endDate().isBefore(edu.startDate())) {
                        throw new AppException(ErrorCode.INVALID_DATE_RANGE);
                    }
                }
            }
        }

        List<Map<String, Object>> eduMaps = education != null
                ? education.stream().map(this::educationToMap).collect(Collectors.toList())
                : new ArrayList<>();

        employee.setEducation(eduMaps);
        employeeRepository.save(employee);

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());
        return toResponse(employee, certificates);
    }

    /**
     * Lấy danh sách chứng chỉ của employee hiện tại.
     */
    @Transactional(readOnly = true)
    public List<EmployeeResponse.CertificateResponse> getCertificates(Long userId) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        return certificateRepository.findByEmployeeId(employee.getId())
                .stream()
                .map(cert -> EmployeeResponse.CertificateResponse.builder()
                        .id(cert.getId())
                        .name(cert.getName())
                        .issuer(cert.getIssuer())
                        .issueDate(cert.getIssueDate())
                        .expiryDate(cert.getExpiryDate())
                        .credentialUrl(cert.getCredentialUrl())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Thêm chứng chỉ mới.
     */
    @Transactional
    public EmployeeResponse.CertificateResponse addCertificate(
            Long userId, CertificateRequest request
    ) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        Certificate certificate = new Certificate();
        certificate.setEmployee(employee);
        certificate.setName(request.name());
        certificate.setIssuer(request.issuer());
        certificate.setIssueDate(request.issueDate());
        certificate.setExpiryDate(request.expiryDate());
        certificate.setCredentialUrl(request.credentialUrl());

        certificateRepository.save(certificate);
        log.info("Certificate added: {} for employee {}", certificate.getName(), employee.getId());

        return toCertificateResponse(certificate);
    }

    /**
     * Cập nhật chứng chỉ.
     */
    @Transactional
    public EmployeeResponse.CertificateResponse updateCertificate(
            Long userId, Long certId, CertificateRequest request
    ) {
        Certificate certificate = certificateRepository.findById(certId)
                .orElseThrow(() -> new AppException(ErrorCode.CERTIFICATE_NOT_FOUND));

        // Chỉ chủ sở hữu mới được sửa
        if (!certificate.getEmployee().getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        certificate.setName(request.name());
        certificate.setIssuer(request.issuer());
        certificate.setIssueDate(request.issueDate());
        certificate.setExpiryDate(request.expiryDate());
        certificate.setCredentialUrl(request.credentialUrl());

        certificateRepository.save(certificate);
        log.info("Certificate updated: {} (id={})", certificate.getName(), certId);

        return toCertificateResponse(certificate);
    }

    /**
     * Xoá chứng chỉ.
     */
    @Transactional
    public void deleteCertificate(Long userId, Long certId) {
        Certificate certificate = certificateRepository.findById(certId)
                .orElseThrow(() -> new AppException(ErrorCode.CERTIFICATE_NOT_FOUND));

        if (!certificate.getEmployee().getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        certificateRepository.delete(certificate);
        log.info("Certificate deleted: id={}", certId);
    }

    /**
     * Upload avatar cho employee.
     * Avatar được lưu ở User entity (không phải Employee).
     * Nếu đã có avatar cũ, tự động xoá trên Cloudinary trước khi upload mới.
     *
     * @param userId ID của user (từ SecurityContext)
     * @param file   File ảnh avatar (multipart)
     * @return EmployeeResponse đã cập nhật
     */
    @Transactional
    public EmployeeResponse uploadAvatar(Long userId, MultipartFile file) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        var user = employee.getUser();
        String oldAvatarUrl = user.getAvatarUrl(); // Lưu URL cũ trước khi overwrite

        // Upload avatar mới lên Cloudinary (xoá cũ sau, khi save OK)
        String folder = "employees/" + employee.getId() + "/avatar";
        String uploadedUrl = cloudinaryService.uploadFile(file, folder, CloudinaryService.IMAGE_TYPES);

        user.setAvatarUrl(uploadedUrl);

        // Save DB — nếu fail thì cleanup orphan file
        try {
            userRepository.save(user);
        } catch (Exception e) {
            // Upload thành công nhưng DB fail → xoá file vừa upload
            try {
                cloudinaryService.deleteFile(uploadedUrl);
                log.warn("Cleaned up orphan avatar after DB failure: {}", uploadedUrl);
            } catch (Exception cleanupEx) {
                log.warn("Failed to cleanup orphan avatar: {}", uploadedUrl, cleanupEx);
            }
            throw e;
        }

        // Save DB thành công → giờ mới xoá avatar cũ
        if (oldAvatarUrl != null && !oldAvatarUrl.isBlank()) {
            try {
                cloudinaryService.deleteFile(oldAvatarUrl);
            } catch (Exception e) {
                log.warn("Failed to delete old avatar from Cloudinary: {}", oldAvatarUrl, e);
            }
        }

        log.info("Avatar updated for employee {} (userId={})", employee.getId(), userId);

        List<Certificate> certificates = certificateRepository.findByEmployeeId(employee.getId());
        return toResponse(employee, certificates);
    }

    private EmployeeResponse.CertificateResponse toCertificateResponse(Certificate cert) {
        return EmployeeResponse.CertificateResponse.builder()
                .id(cert.getId())
                .name(cert.getName())
                .issuer(cert.getIssuer())
                .issueDate(cert.getIssueDate())
                .expiryDate(cert.getExpiryDate())
                .credentialUrl(cert.getCredentialUrl())
                .build();
    }

    private Map<String, Object> educationToMap(EducationDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("school", dto.school());
        map.put("degree", dto.degree());
        map.put("major", dto.major());
        map.put("description", dto.description());
        map.put("startDate", dto.startDate() != null ? dto.startDate().toString() : null);
        map.put("endDate", dto.endDate() != null ? dto.endDate().toString() : null);
        map.put("isCurrent", dto.isCurrent());
        return map;
    }

    private Map<String, Object> experienceToMap(ExperienceDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("company", dto.company());
        map.put("position", dto.position());
        map.put("description", dto.description());
        map.put("startDate", dto.startDate() != null ? dto.startDate().toString() : null);
        map.put("endDate", dto.endDate() != null ? dto.endDate().toString() : null);
        map.put("isCurrent", dto.isCurrent());
        return map;
    }

    private EmployeeResponse toResponse(Employee employee, List<Certificate> certificates) {
        List<EmployeeResponse.CertificateResponse> certResponses = certificates.stream()
                .map(cert -> EmployeeResponse.CertificateResponse.builder()
                        .id(cert.getId())
                        .name(cert.getName())
                        .issuer(cert.getIssuer())
                        .issueDate(cert.getIssueDate())
                        .expiryDate(cert.getExpiryDate())
                        .credentialUrl(cert.getCredentialUrl())
                        .build())
                .collect(Collectors.toList());

        return EmployeeResponse.builder()
                .id(employee.getId())
                .userId(employee.getUser().getId())
                .fullName(employee.getUser().getFullName())
                .avatarUrl(employee.getUser().getAvatarUrl())
                .phone(employee.getPhone())
                .dateOfBirth(employee.getDateOfBirth())
                .gender(employee.getGender())
                .city(employee.getCity())
                .address(employee.getAddress())
                .cvUrl(employee.getCvUrl())
                .githubUrl(employee.getGithubUrl())
                .linkedinUrl(employee.getLinkedinUrl())
                .portfolioUrl(employee.getPortfolioUrl())
                .isPublic(employee.getIsPublic())
                .isOpenToWork(employee.getIsOpenToWork())
                .title(employee.getTitle())
                .bio(employee.getBio())
                .skills(employee.getSkills())
                .experiences(employee.getExperiences())
                .education(employee.getEducation())
                .certificates(certResponses)
                .createdAt(employee.getCreatedAt())
                .updatedAt(employee.getUpdatedAt())
                .build();
    }
}
