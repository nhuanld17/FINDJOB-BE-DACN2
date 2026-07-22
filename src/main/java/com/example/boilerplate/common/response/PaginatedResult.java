package com.example.boilerplate.common.response;

import lombok.Builder;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Builder
public record PaginatedResult<T>(
        List<T> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious
) {
    /**
     * Dùng khi muốn chuyển dữ liệu từ Entity sang DTO.
     */
    public static <T, R> PaginatedResult<R> fromPage(
            Page<T> page,
            Function<? super T, ? extends R> mapper
    ) {
        List<R> mappedItems = page.getContent()
                .stream()
                .map(mapper)
                .collect(Collectors.toUnmodifiableList());

        return new PaginatedResult<>(
                mappedItems,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext(),
                page.hasPrevious()
        );
    }

    // Dùng khi chỉ cần chuyển từ Page<T> sang PaginatedResult<T>
    public static <T> PaginatedResult<T> fromPage(Page<T> page) {
        return fromPage(page, Function.identity());
    }
}
