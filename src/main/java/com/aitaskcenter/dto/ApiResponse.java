package com.aitaskcenter.dto;

public record ApiResponse<T>(int code, T data, String msg) {
    // 方法：ok
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, data, "获取成功");
    }

    // 方法：ok
    public static <T> ApiResponse<T> ok(T data, String msg) {
        return new ApiResponse<>(0, data, msg);
    }

    // 方法：error
    public static <T> ApiResponse<T> error(String msg) {
        return new ApiResponse<>(7, null, msg);
    }
}
