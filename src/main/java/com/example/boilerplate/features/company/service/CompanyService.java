package com.example.boilerplate.features.company.service;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.constant.RoleEnum;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.application.repository.ApplicationRepository;
import com.example.boilerplate.features.company.dto.request.UpdateCompanyRequest;
import com.example.boilerplate.features.company.dto.response.CompanyResponse;
import com.example.boilerplate.features.company.dto.response.CompanyStatsResponse;
import com.example.boilerplate.features.company.dto.response.CompanySummaryResponse;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.querydsl.CompanyQueryDSL;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.job.repository.JobRepository;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.infrastructure.cloudinary.CloudinaryService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.example.boilerplate.common.constant.JobStatus;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyQueryDSL companyQueryDSL;
    private final CloudinaryService cloudinaryService;
    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;

    private static final Pattern NON_LATIN = Pattern.compile("[^a-z0-9-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]+");

    // ==================== AUTO-CREATE (verify OTP flow) ====================

    /**
     * Tự tạo company cho employer vừa verify OTP thành công.
     * Idempotent: owner đã có company thì trả về company đó (verifyOtp re-entry an toàn).
     * city để null, employer bổ sung sau qua PUT.
     */
    @Transactional
    public Company createCompanyForOwner(User owner, String companyName) {
        // Lấy công ty hiện tại của user
        var existing = companyRepository.findByOwner(owner);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Tạo chuỗi sug dựa trên tên Company
        String slug = generateSlug(companyName);

        if (slug.isBlank()) {
            slug = "company"; // tên toàn ký tự đặc biệt → slug rỗng
        }

        // Nếu có công ty trùng slug thì nối thêm thời gian hiện tại vào chuỗi
        if (companyRepository.findBySlug(slug).isPresent()) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        Company company = new Company();
        company.setOwner(owner);
        company.setName(companyName.trim());
        company.setSlug(slug);
        companyRepository.save(company);
        return company;
    }

    // ==================== READ ====================

    @Transactional(readOnly = true)
    public CompanyResponse getCompanyById(Long id) {
        Company company = companyRepository.findByIdWithOwner(id)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        return toResponse(company);
    }

    @Transactional(readOnly = true)
    public CompanyResponse getCompanyBySlug(String slug) {
        Company company = companyRepository.findBySlugWithOwner(slug)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        return toResponse(company);
    }

    @Transactional(readOnly = true)
    public CompanyResponse getMyCompany(CustomUserDetails userDetails) {
        // Kiểm user có role COMPANY hay không
        boolean isCompany = userDetails.getRoles().stream()
                .anyMatch(role -> role.getName() == RoleEnum.COMPANY);

        if (!isCompany) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        // Lấy entity Company của user hiện tại
        Company company = companyRepository.findByOwnerId(userDetails.getId())
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        // Nếu Company đã bị xóa -> EXP
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        // Owner info lấy từ SecurityContext
        return CompanyResponse.builder()
                .id(company.getId())
                .ownerId(userDetails.getId())
                .ownerName(userDetails.getUsername())
                .name(company.getName())
                .slug(company.getSlug())
                .description(company.getDescription())
                .logoUrl(company.getLogoUrl())
                .coverUrl(company.getCoverUrl())
                .website(company.getWebsite())
                .companySize(company.getCompanySize())
                .industry(company.getIndustry())
                .city(company.getCity())
                .address(company.getAddress())
                .email(company.getEmail())
                .phone(company.getPhone())
                .facebookUrl(company.getFacebookUrl())
                .linkedinUrl(company.getLinkedinUrl())
                .contactPosition(company.getContactPosition())
                .followerCount(company.getFollowerCount())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }

    // ==================== UPLOAD LOGO / COVER ====================

    /**
     * Upload logo cho công ty.
     * Nếu đã có logo cũ, tự động xóa trên Cloudinary trước khi upload logo mới.
     *
     * @param id     ID của công ty
     * @param userId ID của chủ sở hữu
     * @param file   File ảnh logo (multipart)
     * @return CompanyResponse đã cập nhật
     */
    @Transactional
    public CompanyResponse uploadLogo(Long id, Long userId, MultipartFile file) {
        Company company = findCompanyOrThrow(id);

        if (!company.getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        String oldLogoUrl = company.getLogoUrl(); // Lưu URL cũ trước khi overwrite

        // Upload logo mới lên Cloudinary (xoá cũ sau, khi save OK)
        String folder = "companies/" + id + "/logo";
        String uploadedUrl = cloudinaryService.uploadFile(file, folder, CloudinaryService.IMAGE_TYPES);

        company.setLogoUrl(uploadedUrl);

        // Save DB — nếu fail thì cleanup orphan file
        try {
            companyRepository.save(company);
        } catch (Exception e) {
            try {
                cloudinaryService.deleteFile(uploadedUrl);
                log.warn("Cleaned up orphan logo after DB failure: {}", uploadedUrl);
            } catch (Exception cleanupEx) {
                log.warn("Failed to cleanup orphan logo: {}", uploadedUrl, cleanupEx);
            }
            throw e;
        }

        // Save DB thành công → giờ mới xoá logo cũ
        if (oldLogoUrl != null && !oldLogoUrl.isBlank()) {
            try {
                cloudinaryService.deleteFile(oldLogoUrl);
            } catch (Exception e) {
                log.warn("Failed to delete old logo from Cloudinary: {}", oldLogoUrl, e);
            }
        }

        return toResponse(company);
    }

    /**
     * Upload ảnh bìa cho công ty.
     * Nếu đã có ảnh bìa cũ, tự động xóa trên Cloudinary trước khi upload ảnh mới.
     *
     * @param id     ID của công ty
     * @param userId ID của chủ sở hữu
     * @param file   File ảnh bìa (multipart)
     * @return CompanyResponse đã cập nhật
     */
    @Transactional
    public CompanyResponse uploadCover(Long id, Long userId, MultipartFile file) {
        Company company = findCompanyOrThrow(id);

        if (!company.getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        String oldCoverUrl = company.getCoverUrl(); // Lưu URL cũ trước khi overwrite

        // Upload ảnh bìa mới lên Cloudinary (xoá cũ sau, khi save OK)
        String folder = "companies/" + id + "/cover";
        String uploadedUrl = cloudinaryService.uploadFile(file, folder, CloudinaryService.IMAGE_TYPES);

        company.setCoverUrl(uploadedUrl);

        // Save DB — nếu fail thì cleanup orphan file
        try {
            companyRepository.save(company);
        } catch (Exception e) {
            try {
                cloudinaryService.deleteFile(uploadedUrl);
                log.warn("Cleaned up orphan cover after DB failure: {}", uploadedUrl);
            } catch (Exception cleanupEx) {
                log.warn("Failed to cleanup orphan cover: {}", uploadedUrl, cleanupEx);
            }
            throw e;
        }

        // Save DB thành công → giờ mới xoá ảnh bìa cũ
        if (oldCoverUrl != null && !oldCoverUrl.isBlank()) {
            try {
                cloudinaryService.deleteFile(oldCoverUrl);
            } catch (Exception e) {
                log.warn("Failed to delete old cover from Cloudinary: {}", oldCoverUrl, e);
            }
        }

        return toResponse(company);
    }

    // ==================== UPDATE ====================

    @Transactional
    public CompanyResponse updateCompany(Long companyId, Long userId, UpdateCompanyRequest request) {
        Company company = findCompanyOrThrow(companyId);

        // Chỉ owner mới được update
        if (!company.getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        if (request.name() != null) {
            company.setName(request.name().trim());
            // Cập nhật slug
            String newSlug = generateSlug(request.name().trim());
            var existing = companyRepository.findBySlug(newSlug);
            if (existing.isPresent() && !existing.get().getId().equals(companyId)) {
                newSlug = newSlug + "-" + System.currentTimeMillis();
            }
            company.setSlug(newSlug);
        }
        if (request.description() != null) company.setDescription(request.description());
        if (request.logoUrl() != null) company.setLogoUrl(request.logoUrl());
        if (request.coverUrl() != null) company.setCoverUrl(request.coverUrl());
        if (request.website() != null) company.setWebsite(request.website());
        if (request.companySize() != null) company.setCompanySize(request.companySize());
        if (request.industry() != null) company.setIndustry(request.industry());
        if (request.city() != null) company.setCity(request.city());
        if (request.address() != null) company.setAddress(request.address());
        if (request.email() != null) company.setEmail(request.email());
        if (request.phone() != null) company.setPhone(request.phone());
        if (request.facebookUrl() != null) company.setFacebookUrl(request.facebookUrl());
        if (request.linkedinUrl() != null) company.setLinkedinUrl(request.linkedinUrl());
        if (request.contactPosition() != null) company.setContactPosition(request.contactPosition());

        companyRepository.save(company);

        return toResponse(company);
    }

    // ==================== DELETE (soft) ====================

    @Transactional
    public void deleteCompany(Long id, Long userId) {
        Company company = findCompanyOrThrow(id);

        // Chỉ owner mới được xoá
        if (!company.getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        company.setDeleted(true);
        companyRepository.save(company);
    }

    // ==================== PRIVATE HELPERS ====================

    private Company findCompanyOrThrow(Long id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        return company;
    }

    private CompanyResponse toResponse(Company company) {
        return CompanyResponse.builder()
                .id(company.getId())
                .ownerId(company.getOwner().getId())
                .ownerName(company.getOwner().getUsername())
                .name(company.getName())
                .slug(company.getSlug())
                .description(company.getDescription())
                .logoUrl(company.getLogoUrl())
                .coverUrl(company.getCoverUrl())
                .website(company.getWebsite())
                .companySize(company.getCompanySize())
                .industry(company.getIndustry())
                .city(company.getCity())
                .address(company.getAddress())
                .email(company.getEmail())
                .phone(company.getPhone())
                .facebookUrl(company.getFacebookUrl())
                .linkedinUrl(company.getLinkedinUrl())
                .contactPosition(company.getContactPosition())
                .followerCount(company.getFollowerCount())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }

    /**
     * Tạo slug từ tên công ty (dùng cho URL).
     * <p>
     * Quy trình từng bước — ví dụ với input {@code "Công ty TNHH!!!"}:
     * <pre>
     *  1. NFD normalize  → "Công ty TNHH!!!"   (tách chữ và dấu ra riêng)
     *  2. Xoá dấu        → "Cong ty TNHH!!!"    (bỏ các dấu sắc/huyền/ngã...)
     *  3. Lowercase + đ→d→ "cong ty tnhh!!!"    (về chữ thường, đ → d)
     *  4. Whitespace → - → "cong-ty-tnhh!!!"    (khoảng trắng thành dấu gạch)
     *  5. Xoá ký tự lạ  → "cong-ty-tnhh"       (chỉ giữ a-z, 0-9 và dấu gạch)
     *  6. Gộp --        → "cong-ty-tnhh"       (nếu có -- thì thành -)
     *  7. Xoá - đầu/cuối→ "cong-ty-tnhh"       (không để - ở đầu hay cuối)
     * </pre>
     *
     * @param name tên công ty (có thể có tiếng Việt, ký tự đặc biệt, ...)
     * @return slug URL-friendly, VD: "Công ty TNHH ABC" → "cong-ty-tnhh-abc"
     */
    private String generateSlug(String name) {
        // Bước 1: Chuẩn hoá Unicode dạng NFD — tách chữ cái và dấu thành 2 ký tự riêng
        // VD: 'ô' → 'o' + dấu mũ ◌̂  ; 'à' → 'a' + dấu huyền ◌̀
        // Mục đích: để bước 2 có thể xoá riêng phần dấu mà không ảnh hưởng chữ cái
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD);

        // Bước 2: Xoá tất cả dấu tiếng Việt (dấu sắc, huyền, hỏi, ngã, nặng, mũ, ...)
        // \p{InCombiningDiacriticalMarks} = Unicode class chứa các ký tự dấu
        // VD: "Công ty" → sau bước 1 là "Công ty" → sau bước 2 là "Cong ty"
        slug = slug.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Bước 3: Về chữ thường + xử lý riêng chữ "đ" → "d"
        // (Normalizer không xử lý được đ → d vì đ là chữ riêng, không phải d + dấu)
        slug = slug.toLowerCase(Locale.ROOT)
                .replaceAll("đ", "d");

        // Bước 4: Thay khoảng trắng (\s+) bằng dấu gạch ngang -
        // VD: "cong ty tnhh!!!" → "cong-ty-tnhh!!!"
        slug = WHITESPACE.matcher(slug).replaceAll("-");

        // Bước 5: Xoá tất cả ký tự không phải a-z, 0-9 hoặc dấu gạch -
        // Mục đích: loại bỏ !, @, #, $, %, dấu câu, ...
        // VD: "cong-ty-tnhh!!!" → "cong-ty-tnhh"
        slug = NON_LATIN.matcher(slug).replaceAll("");

        // Bước 6: Gộp nhiều dấu gạch liên tiếp thành 1 dấu gạch
        // VD: "cong---ty" → "cong-ty" (phòng trường hợp đầu vào có nhiều khoảng trắng)
        slug = slug.replaceAll("-{2,}", "-");

        // Bước 7: Xoá dấu gạch ở đầu (`^-`) và cuối (`-$`) chuỗi
        // VD: "-cong-ty-" → "cong-ty" (phòng trường hợp đầu vào có khoảng trắng ở rìa)
        slug = slug.replaceAll("^-|-$", "");

        return slug;
    }

    // ==================== DASHBOARD STATS ====================

    /**
     * Lấy thống kê tổng quan cho Company Dashboard.
     *
     * @param userId ID của user (chủ công ty, từ SecurityContext)
     * @return CompanyStatsResponse với các chỉ số tổng quan
     */
    @Transactional(readOnly = true)
    public CompanyStatsResponse getCompanyStats(Long userId) {
        Company company = companyRepository.findByOwnerId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }

        // 1. Đếm tổng số job (chưa xoá)
        long totalJobs = jobRepository.countByCompanyIdAndDeletedFalse(company.getId());

        // 2. Đếm job ACTIVE, chưa expired
        long activeJobs = jobRepository.countActiveByCompanyId(company.getId(), JobStatus.ACTIVE);

        // 3. Đếm tổng số ứng viên đã apply vào tất cả job của công ty
        long totalApplicants = applicationRepository.countByCompanyId(company.getId());

        // 4. Số follower (đã cache trên entity)
        int totalFollowers = company.getFollowerCount() != null ? company.getFollowerCount() : 0;

        // 5. 5 application gần nhất
        List<CompanyStatsResponse.RecentApplication> recentApplications =
                applicationRepository.findTopByCompanyId(company.getId(),
                                PageRequest.of(0, 5))
                        .stream()
                        .map(app -> new CompanyStatsResponse.RecentApplication(
                                app.getId(),
                                app.getJob().getTitle(),
                                app.getEmployee().getUser().getFullName(),
                                app.getEmployee().getUser().getAvatarUrl(),
                                app.getAppliedAt()
                        ))
                        .collect(Collectors.toList());

        // 6. Gom nhóm application theo trạng thái
        Map<String, Long> applicationsByStatus = new LinkedHashMap<>();
        // Khởi tạo tất cả trạng thái với 0, đảm bảo luôn có đủ key
        for (var status : com.example.boilerplate.common.constant.ApplicationStatus.values()) {
            applicationsByStatus.put(status.name(), 0L);
        }
        // Ghi đè bằng kết quả thực tế
        applicationRepository.countByStatusGroupByCompanyId(company.getId())
                .forEach(row -> {
                    String statusName = ((Enum<?>) row[0]).name();
                    Long count = (Long) row[1];
                    applicationsByStatus.put(statusName, count);
                });

        log.info("Company dashboard stats fetched for companyId={}, userId={}",
                company.getId(), userId);

        return new CompanyStatsResponse(
                totalJobs,
                activeJobs,
                totalApplicants,
                totalFollowers,
                recentApplications,
                applicationsByStatus
        );
    }

    // Tìm kiếm công ty với phân trang và sắp xếp
    public PaginatedResult<CompanySummaryResponse> getPaginatedCompanies(
            int page, int size, String search, String industry, City city, String sort
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sort));

        String cityStr = city != null ? city.name() : null;
        Page<CompanySummaryResponse> result = companyQueryDSL.searchCompanies(
                search,
                industry,
                cityStr,
                pageable
        );

        return PaginatedResult.fromPage(result);
    }
}
