package com.example.boilerplate.features.job.controller;

import com.example.boilerplate.common.response.APIResponse;
import com.example.boilerplate.features.job.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** Lấy danh sách categories — Public */
    @GetMapping
    public ResponseEntity<APIResponse<List<CategoryService.CategoryResponse>>> getAllCategories() {
        List<CategoryService.CategoryResponse> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(APIResponse.success(categories));
    }

    /** Tạo category mới — Admin */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CategoryService.CategoryResponse>> createCategory(
            @RequestParam String name,
            @RequestParam(required = false) String description
    ) {
        CategoryService.CategoryResponse response = categoryService.createCategory(name, description);
        return ResponseEntity.ok(APIResponse.success(response));
    }

    /** Xoá category — Admin */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<Void>> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.ok(APIResponse.success());
    }
}
