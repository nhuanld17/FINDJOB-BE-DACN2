package com.example.boilerplate.features.company.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.company.dto.request.UpdateCompanyRequest;
import com.example.boilerplate.features.company.dto.response.CompanyResponse;
import com.example.boilerplate.features.company.dto.response.CompanySummaryResponse;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.querydsl.CompanyQueryDSL;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.user.entity.User;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyQueryDSL companyQueryDSL;

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
        var existing = companyRepository.findByOwner(owner);
        if (existing.isPresent()) {
            return existing.get();
        }

        String slug = generateSlug(companyName);
        if (slug.isBlank()) {
            slug = "company"; // tên toàn ký tự đặc biệt → slug rỗng
        }
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
        Company company = companyRepository.findByOwnerId(userDetails.getId())
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        if (company.isDeleted()) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        // Owner info lấy từ SecurityContext — không cần JOIN FETCH User
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
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }

    // ==================== UPDATE ====================

    @Transactional
    public CompanyResponse updateCompany(Long id, Long userId, UpdateCompanyRequest request) {
        Company company = findCompanyOrThrow(id);

        // Chỉ owner mới được update
        if (!company.getOwner().getId().equals(userId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }

        if (request.name() != null) {
            company.setName(request.name().trim());
            // Cập nhật slug
            String newSlug = generateSlug(request.name().trim());
            var existing = companyRepository.findBySlug(newSlug);
            if (existing.isPresent() && !existing.get().getId().equals(id)) {
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
        if (request.city() != null) company.setCity(request.city().trim());
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
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }

    /**
     * Tạo slug từ tên công ty.
     * VD: "Công ty TNHH ABC" → "cong-ty-tnhh-abc"
     */
    private String generateSlug(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD);
        slug = slug.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        slug = slug.toLowerCase(Locale.ROOT)
                .replaceAll("đ", "d");
        slug = WHITESPACE.matcher(slug).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^-|-$", "");
        return slug;
    }

    public PaginatedResult<CompanySummaryResponse> getPaginatedCompanies(
            int page, int size, String search, String industry, String city, String sort
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sort));

        Page<CompanySummaryResponse> result = companyQueryDSL.searchCompanies(
                search,
                industry,
                city,
                pageable
        );

        return PaginatedResult.fromPage(result);
    }
}
