package com.example.boilerplate.features.employee.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.common.response.PaginatedResult;
import com.example.boilerplate.features.employee.dto.response.FollowedCompanyResponse;
import com.example.boilerplate.features.employee.service.FollowService;
import com.example.boilerplate.infrastructure.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller cho follow/unfollow company.
 * 
 * Chỉ user có role USER (người xin việc) mới có thể follow.
 */
@RestController
@RequestMapping("/api/v1/follows")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** Follow một company */
    @PostMapping("/companies/{companyId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> follow(
            @PathVariable Long companyId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        followService.follow(userDetails.getId(), companyId);
        return ResponseEntity.ok(APIResponse.success());
    }

    /** Unfollow một company */
    @DeleteMapping("/companies/{companyId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<Void>> unfollow(
            @PathVariable Long companyId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        followService.unfollow(userDetails.getId(), companyId);
        return ResponseEntity.ok(APIResponse.success());
    }

    /** Lấy danh sách company đang follow (phân trang, kèm thông tin cơ bản) */
    @GetMapping("/companies")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<APIResponse<PaginatedResult<FollowedCompanyResponse>>> getFollowedCompanies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "followedAt"));
        PaginatedResult<FollowedCompanyResponse> result = followService.getFollowedCompanies(userDetails.getId(), pageable);
        return ResponseEntity.ok(APIResponse.success(result));
    }

    /** Kiểm tra có đang follow company này không + đếm follower */
    @GetMapping("/companies/{companyId}/status")
    public ResponseEntity<APIResponse<Map<String, Object>>> getFollowStatus(
            @PathVariable Long companyId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        long followerCount = followService.getFollowerCount(companyId);
        boolean isFollowing = userDetails != null
                && userDetails.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_USER"))
                && followService.isFollowing(userDetails.getId(), companyId);

        return ResponseEntity.ok(APIResponse.success(Map.of(
                "followerCount", followerCount,
                "isFollowing", isFollowing
        )));
    }
}
