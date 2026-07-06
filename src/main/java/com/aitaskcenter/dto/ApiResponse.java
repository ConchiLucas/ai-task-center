package com.aitaskcenter.dto;

public record ApiResponse<T>(int code, T data, String msg) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "获取成功");
    }

    public static <T> ApiResponse<T> ok(T data, String msg) {
        return new ApiResponse<>(0, data, msg);
    }

    public static <T> ApiResponse<T> error(String msg) {
        return new ApiResponse<>(7, null, msg);
    }
}
