package com.example.boilerplate.infrastructure.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final MailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        mailSender.sendHtmlEmail(to, subject, htmlContent);
    }

    public void sendOtpEmail(String to, String username, String otp) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("otp", otp);
        context.setVariable("expireMinutes", 5);

        String content = templateEngine.process("email/otp", context);
        sendHtmlEmail(to, "Your OTP Code", content);
    }

    public void sendWelcomeEmail(String to, String username) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("email", to);

        String content = templateEngine.process("email/welcome", context);
        sendHtmlEmail(to, "Welcome to Boilerplate!", content);
    }

    /**
     * Gửi email thông báo hồ sơ ĐƯỢC DUYỆT (ACCEPTED) cho ứng viên.
     *
     * @param to          Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName    Tên ứng viên
     * @param jobTitle    Tên job đã ứng tuyển
     * @param companyName Tên công ty nhà tuyển dụng
     */
    public void sendApplicationAcceptedEmail(String to, String fullName,
                                             String jobTitle, String companyName) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("jobTitle", jobTitle);
        context.setVariable("companyName", companyName);

        String content = templateEngine.process("email/application-accepted", context);
        sendHtmlEmail(to, "Hồ sơ của bạn đã được duyệt - " + companyName, content);
    }

    /**
     * Gửi email thông báo hồ sơ BỊ TỪ CHỐI (REJECTED) cho ứng viên, kèm lý do từ chối.
     *
     * @param to             Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName       Tên ứng viên
     * @param jobTitle       Tên job đã ứng tuyển
     * @param companyName    Tên công ty nhà tuyển dụng
     * @param rejectedReason Lý do từ chối (đã validate bắt buộc ở service)
     */
    public void sendApplicationRejectedEmail(String to, String fullName,
                                             String jobTitle, String companyName,
                                             String rejectedReason) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("jobTitle", jobTitle);
        context.setVariable("companyName", companyName);
        context.setVariable("rejectedReason", rejectedReason);

        String content = templateEngine.process("email/application-rejected", context);
        sendHtmlEmail(to, "Kết quả ứng tuyển - " + companyName, content);
    }
}
