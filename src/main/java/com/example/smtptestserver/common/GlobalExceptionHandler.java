package com.example.smtptestserver.common;

import com.example.smtptestserver.common.response.CommonResponse;
import com.example.smtptestserver.common.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * REST API 전용 예외 처리 핸들러.
 * annotations 속성으로 @RestController만 대상으로 한다.
 */
@Slf4j
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class GlobalExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResponse<?> handleValidationException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("유효성 검증 실패: {}", errorMessage);
        return CommonResponse.fail(errorMessage, ErrorCode.COMMON_INVALID_PARAMETER.name());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public CommonResponse<?> handleException(Exception e) {
        log.error("예기치 못한 오류 발생: {}", e.getMessage(), e);
        return CommonResponse.fail(ErrorCode.COMMON_SYSTEM_ERROR);
    }
}
