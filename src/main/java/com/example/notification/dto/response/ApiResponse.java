package com.example.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    T data,
    String message
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> accepted(T data) {
        return new ApiResponse<>(true, data, "요청이 접수되었습니다. 알림은 비동기로 처리됩니다.");
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
