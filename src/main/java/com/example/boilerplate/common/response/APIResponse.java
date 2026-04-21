package com.example.boilerplate.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// Bỏ qua field null trong JSON
@JsonInclude(JsonInclude.Include.NON_NULL)
public class APIResponse <T> {

    @Builder.Default
    private int code = 1000; // 1000 = success

    private String message;
    private T data;

    // Factory method cho success và có data
    public static <T> APIResponse<T> success(T data) {
        return APIResponse.<T>builder()
                .code(1000)
                .message("Success")
                .data(data)
                .build();
    }

    // Factory method cho success và ko có data
    public static <T> APIResponse<T> success() {
        return APIResponse.<T>builder()
                .code(1000)
                .message("Success")
                .build();
    }
}
