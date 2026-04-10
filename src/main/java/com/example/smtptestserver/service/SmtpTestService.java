package com.example.smtptestserver.service;

import com.example.smtptestserver.dto.EncryptionType;
import com.example.smtptestserver.dto.SmtpTestRequest;
import com.example.smtptestserver.dto.SmtpTestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;

@Slf4j
@Service
public class SmtpTestService {

    @Value("${smtp-test.timeout.connection:5000}")
    private int connectionTimeout;

    @Value("${smtp-test.timeout.read:5000}")
    private int readTimeout;

    @Value("${smtp-test.timeout.write:5000}")
    private int writeTimeout;

    /**
     * 사용자 입력값 기반으로 동적 JavaMailSender를 생성하고 테스트 메일을 발송한다.
     */
    public SmtpTestResult sendTestMail(SmtpTestRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            JavaMailSender mailSender = createMailSender(request);

            MimeMessage message = ((JavaMailSenderImpl) mailSender).createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(request.getFromEmail());
            helper.setTo(request.getToEmail());
            helper.setSubject(request.getSubject());
            helper.setText(request.getBody() != null ? request.getBody() : "", false);

            mailSender.send(message);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("메일 발송 성공: host={}, port={}, from={}, to={}, elapsed={}ms",
                    request.getHost(), request.getPort(), request.getFromEmail(), request.getToEmail(), elapsed);

            return SmtpTestResult.success(elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("메일 발송 실패: host={}, port={}, error={}",
                    request.getHost(), request.getPort(), e.getMessage());

            return analyzeException(e, elapsed);
        }
    }

    /**
     * 요청값 기반으로 JavaMailSenderImpl을 동적으로 생성한다.
     * spring.mail.* 설정을 사용하지 않고, 매 요청마다 새로 만든다.
     */
    private JavaMailSender createMailSender(SmtpTestRequest request) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(request.getHost());
        sender.setPort(request.getPort());

        // 인증 설정
        if (request.isAuthEnabled()) {
            sender.setUsername(request.getUsername());
            sender.setPassword(request.getPassword());
        }

        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");

        // 타임아웃 설정
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectionTimeout));
        props.put("mail.smtp.timeout", String.valueOf(readTimeout));
        props.put("mail.smtp.writetimeout", String.valueOf(writeTimeout));

        // 인증
        props.put("mail.smtp.auth", String.valueOf(request.isAuthEnabled()));

        // 암호화 설정
        configureEncryption(props, request.getEncryption(), sender);

        return sender;
    }

    /**
     * 암호화 방식에 따라 JavaMail 프로퍼티를 설정한다.
     */
    private void configureEncryption(Properties props, EncryptionType encryption, JavaMailSenderImpl sender) {
        switch (encryption) {
            case NONE -> {
                props.put("mail.smtp.starttls.enable", "false");
                props.put("mail.smtp.ssl.enable", "false");
            }
            case SSL -> {
                // SMTPS (포트 465 등) — 연결 시작부터 SSL
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.trust", "*");
                props.put("mail.smtp.starttls.enable", "false");
                sender.setProtocol("smtps");
            }
            case TLS -> {
                // STARTTLS (포트 587 등) — 평문 연결 후 TLS 업그레이드
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.trust", "*");
                props.put("mail.smtp.ssl.enable", "false");
            }
        }
    }

    /**
     * 예외를 분석하여 사용자 친화적인 에러 메시지를 생성한다.
     */
    private SmtpTestResult analyzeException(Exception e, long elapsed) {
        Throwable root = findRootCause(e);
        String detail = buildDetailMessage(e);

        // 인증 실패
        if (root instanceof AuthenticationFailedException) {
            return SmtpTestResult.fail(
                    "SMTP 인증에 실패했습니다. 사용자명과 비밀번호를 확인해주세요.",
                    detail, elapsed);
        }

        // 연결 거부
        if (root instanceof ConnectException) {
            return SmtpTestResult.fail(
                    "SMTP 서버에 연결할 수 없습니다. 호스트 주소와 포트를 확인해주세요. " +
                    "방화벽이 해당 포트를 차단하고 있을 수 있습니다.",
                    detail, elapsed);
        }

        // DNS 조회 실패
        if (root instanceof UnknownHostException) {
            return SmtpTestResult.fail(
                    "SMTP 서버 호스트를 찾을 수 없습니다. 호스트 주소를 확인해주세요. " +
                    "DNS 이름이 올바른지 확인하세요.",
                    detail, elapsed);
        }

        // 타임아웃
        if (root instanceof SocketTimeoutException) {
            return SmtpTestResult.fail(
                    "SMTP 서버 연결 시간이 초과되었습니다. " +
                    "서버가 응답하지 않거나, 포트/암호화 설정이 맞지 않을 수 있습니다.",
                    detail, elapsed);
        }

        // SSL/TLS 관련 에러
        if (root instanceof javax.net.ssl.SSLException || root instanceof javax.net.ssl.SSLHandshakeException) {
            return SmtpTestResult.fail(
                    "SSL/TLS 연결에 실패했습니다. 암호화 설정을 확인해주세요. " +
                    "포트에 맞는 암호화 방식(SSL=465, TLS=587, 없음=25)을 선택했는지 확인하세요.",
                    detail, elapsed);
        }

        // MessagingException (일반 메일 에러)
        if (root instanceof MessagingException) {
            String msg = root.getMessage();

            if (msg != null && msg.contains("STARTTLS")) {
                return SmtpTestResult.fail(
                        "STARTTLS 협상에 실패했습니다. 서버가 STARTTLS를 지원하지 않거나, " +
                        "암호화 설정이 맞지 않을 수 있습니다. '암호화 없음'으로 시도해보세요.",
                        detail, elapsed);
            }

            if (msg != null && msg.contains("relay")) {
                return SmtpTestResult.fail(
                        "메일 릴레이가 거부되었습니다. SMTP 서버가 해당 발신/수신 주소의 릴레이를 허용하지 않습니다.",
                        detail, elapsed);
            }
        }

        // 기타 알 수 없는 에러
        return SmtpTestResult.fail(
                "메일 발송 중 오류가 발생했습니다: " + root.getClass().getSimpleName(),
                detail, elapsed);
    }

    private Throwable findRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private String buildDetailMessage(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) sb.append("\n  ← ");
            sb.append(current.getClass().getSimpleName())
              .append(": ")
              .append(current.getMessage());
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }
}
