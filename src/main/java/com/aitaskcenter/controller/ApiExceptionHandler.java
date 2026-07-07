package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class
    })
    // 方法：badRequest
    public ApiResponse<Void> badRequest(Exception ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    // 方法：serverError
    public ApiResponse<Void> serverError(Exception ex) {
        return ApiResponse.error("服务异常: " + ex.getMessage());
    }
}
