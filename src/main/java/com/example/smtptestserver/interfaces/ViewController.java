package com.example.smtptestserver.interfaces;

import com.example.smtptestserver.dto.EncryptionType;
import com.example.smtptestserver.dto.SmtpTestRequest;
import com.example.smtptestserver.dto.SmtpTestResult;
import com.example.smtptestserver.service.SmtpTestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ViewController {

    private final SmtpTestService smtpTestService;

    /**
     * 메인 페이지 — SMTP 테스트 폼
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("request", SmtpTestRequest.defaultRequest());
        model.addAttribute("encryptionTypes", EncryptionType.values());
        return "index";
    }

    /**
     * 메일 발송 처리
     */
    @PostMapping("/send")
    public String sendTestMail(@Valid @ModelAttribute("request") SmtpTestRequest request,
                               BindingResult bindingResult,
                               Model model) {
        model.addAttribute("encryptionTypes", EncryptionType.values());

        // 인증 사용 시 username/password 검증
        if (request.isAuthEnabled()) {
            if (request.getUsername() == null || request.getUsername().isBlank()) {
                bindingResult.rejectValue("username", "NotBlank", "인증 사용 시 사용자명을 입력해주세요.");
            }
            if (request.getPassword() == null || request.getPassword().isBlank()) {
                bindingResult.rejectValue("password", "NotBlank", "인증 사용 시 비밀번호를 입력해주세요.");
            }
        }

        if (bindingResult.hasErrors()) {
            return "index";
        }

        SmtpTestResult result = smtpTestService.sendTestMail(request);
        model.addAttribute("result", result);

        return "index";
    }
}
