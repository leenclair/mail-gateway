package com.example.smtptestserver.interfaces;

import com.example.smtptestserver.common.response.CommonResponse;
import com.example.smtptestserver.dto.SmtpTestRequest;
import com.example.smtptestserver.dto.SmtpTestResult;
import com.example.smtptestserver.service.SmtpTestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MailController {

    private final SmtpTestService smtpTestService;

    /**
     * REST API로 테스트 메일 발송
     */
    @PostMapping("/send")
    public CommonResponse<SmtpTestResult> sendTestMail(@Valid @RequestBody SmtpTestRequest request) {
        SmtpTestResult result = smtpTestService.sendTestMail(request);

        if (result.isSuccess()) {
            return CommonResponse.success(result, result.getMessage());
        } else {
            return CommonResponse.success(result, result.getMessage());
        }
    }
}
