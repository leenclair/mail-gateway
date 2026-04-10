package com.example.smtptestserver.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SmtpTestResult {

    private final boolean success;
    private final String message;
    /** 실패 시 상세 원인 (스택트레이스 요약) */
    private final String detail;
    /** 소요 시간 (ms) */
    private final long elapsedMs;

    public static SmtpTestResult success(long elapsedMs) {
        return SmtpTestResult.builder()
                .success(true)
                .message("메일 발송 성공!")
                .elapsedMs(elapsedMs)
                .build();
    }

    public static SmtpTestResult fail(String message, String detail, long elapsedMs) {
        return SmtpTestResult.builder()
                .success(false)
                .message(message)
                .detail(detail)
                .elapsedMs(elapsedMs)
                .build();
    }
}
