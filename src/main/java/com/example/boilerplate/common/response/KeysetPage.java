package com.example.boilerplate.common.response;

import java.util.List;

public record KeysetPage<T>(
    List<T> items,
    String nextCursor,
    boolean hasMore
) {
    public static <T> KeysetPage<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new KeysetPage<T>(items, nextCursor, hasMore);
    }
}
