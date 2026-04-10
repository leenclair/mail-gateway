package com.example.smtptestserver.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmtpTestRequest {

    // === SMTP 서버 설정 ===

    @NotBlank(message = "SMTP 서버 주소를 입력해주세요.")
    private String host;

    @NotNull(message = "포트를 입력해주세요.")
    @Min(value = 1, message = "포트는 1 이상이어야 합니다.")
    @Max(value = 65535, message = "포트는 65535 이하여야 합니다.")
    private Integer port;

    @NotNull(message = "암호화 방식을 선택해주세요.")
    private EncryptionType encryption;

    // === 인증 설정 ===

    /** 인증 사용 여부 (체크박스) */
    @Builder.Default
    private boolean authEnabled = false;

    /** SMTP 인증 사용자명 (authEnabled=true일 때 필요) */
    private String username;

    /** SMTP 인증 비밀번호 (authEnabled=true일 때 필요) */
    private String password;

    // === 메일 내용 ===

    @NotBlank(message = "보내는 사람 이메일을 입력해주세요.")
    @Email(message = "보내는 사람 이메일 형식이 올바르지 않습니다.")
    private String fromEmail;

    @NotBlank(message = "받는 사람 이메일을 입력해주세요.")
    @Email(message = "받는 사람 이메일 형식이 올바르지 않습니다.")
    private String toEmail;

    @NotBlank(message = "제목을 입력해주세요.")
    private String subject;

    private String body;

    /**
     * 기본값이 채워진 빈 요청 객체 생성 (폼 초기값용)
     */
    public static SmtpTestRequest defaultRequest() {
        return SmtpTestRequest.builder()
                .host("")
                .port(25)
                .encryption(EncryptionType.NONE)
                .authEnabled(false)
                .username("")
                .password("")
                .fromEmail("")
                .toEmail("")
                .subject("SMTP 테스트 메일")
                .body("이 메일은 SMTP 연동 테스트를 위해 발송되었습니다.")
                .build();
    }
}
