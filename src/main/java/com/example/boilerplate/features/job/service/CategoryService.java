package com.example.boilerplate.features.job.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.features.job.entity.Category;
import com.example.boilerplate.features.job.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll()
                .stream()
                .map(cat -> new CategoryResponse(cat.getId(), cat.getName(), cat.getSlug(), cat.getDescription()))
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryResponse createCategory(String name, String description) {
        // Tạo slug từ name
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (categoryRepository.findBySlug(slug).isPresent()) {
            throw new AppException(ErrorCode.CATEGORY_ALREADY_EXISTS);
        }

        Category category = new Category();
        category.setName(name.trim());
        category.setSlug(slug);
        category.setDescription(description != null ? description.trim() : null);
        categoryRepository.save(category);

        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getDescription());
    }

    @Transactional
    public void deleteCategory(Long id) {
        if (!categoryRepository.existsById(id)) {
            throw new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        categoryRepository.deleteById(id);
    }

    public record CategoryResponse(Long id, String name, String slug, String description) {}
}
