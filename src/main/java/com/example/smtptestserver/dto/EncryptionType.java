package com.example.smtptestserver.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SMTP 암호화 방식.
 *
 * "암호화 사용/미사용" 토글과 "암호화 방식(SSL/TLS)" 선택을 별도로 두면
 * "암호화 사용 + 방식 미선택" 같은 모순 상태가 생길 수 있다.
 * 따라서 NONE / SSL / TLS 3가지를 단일 선택으로 통합하여
 * UI에서도 간결하고, 서버에서도 분기 처리가 깔끔하다.
 */
@Getter
@RequiredArgsConstructor
public enum EncryptionType {

    NONE("암호화 없음", "평문 전송 (포트 25)"),
    SSL("SSL", "SSL/SMTPS (포트 465)"),
    TLS("STARTTLS", "STARTTLS (포트 587)");

    private final String label;
    private final String description;
}
