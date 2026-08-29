package com.example.boilerplate.common.outbox.handler;

import com.example.boilerplate.infrastructure.mail.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Xử lý sự kiện email từ outbox.
 *
 * Payload JSON được mong đợi có dạng:
 * {
 *   "to": "nguoi@example.com",
 *   "templateName": "email/otp",
 *   "variables": { "username": "...", "otp": "..." }
 * }
 *
 * Dựa vào templateName, chọn method tương ứng của EmailService.
 * Các method EmailService được gọi đồng bộ – vì consumer chạy trên thread nền,
 * không ảnh hưởng đến response của API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailHandler implements EventHandler {

    /**
     * Các loại event mà handler này hỗ trợ.
     * Đăng ký vào EventHandlerRegistry để consumer biết route.
     */
    private static final Set<String> TYPES = Set.of(
            "EMAIL_OTP",
            "EMAIL_WELCOME",
            "EMAIL_APPLICATION_ACCEPTED",
            "EMAIL_APPLICATION_REJECTED",
            "EMAIL_GENERIC"
    );

    private final ObjectMapper objectMapper;
    private final EmailService emailService;

    @Override
    public Set<String> supportedTypes() {
        return TYPES;
    }

    @Override
    public void handle(String payloadJson) throws Exception {
        // Parse payload
        var root = objectMapper.readTree(payloadJson);
        String to = root.path("to").asText();
        String templateName = root.path("templateName").asText();
        var variables = root.path("variables");
        log.info("[EMAIL] Sending to={} template={}", to, templateName);

        // Dispatch theo templateName
        switch (templateName) {
            case "email/otp":
                emailService.sendOtpEmail(
                        to,
                        variables.path("username").asText(),
                        variables.path("otp").asText()
                );
                break;

            case "email/welcome":
                emailService.sendWelcomeEmail(
                        to,
                        variables.path("username").asText()
                );
                break;

            case "email/application-accepted":
                emailService.sendApplicationAcceptedEmail(
                        to,
                        variables.path("fullName").asText(),
                        variables.path("jobTitle").asText(),
                        variables.path("companyName").asText()
                );
                break;

            case "email/application-rejected":
                emailService.sendApplicationRejectedEmail(
                        to,
                        variables.path("fullName").asText(),
                        variables.path("jobTitle").asText(),
                        variables.path("companyName").asText(),
                        variables.path("rejectedReason").asText()
                );
                break;

            default:
                // EMAIL_GENERIC – không có template định sẵn, lấy subject và html content từ payload
                emailService.sendHtmlEmail(
                        to,
                        root.path("subject").asText(),
                        root.path("htmlContent").asText()
                );
                break;
        }
    }
}