package com.example.boilerplate.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(mimeMessage);

        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email to: " + to, e);
        }
    }

    @Async("emailTaskExecutor")
    public void sendOtpEmail(String to, String username, String otp) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("otp", otp);
        context.setVariable("expireMinutes", 5);

        String content = templateEngine.process("email/otp", context);
        sendHtmlEmail(to, "Your OTP Code", content);
    }

    @Async("emailTaskExecutor")
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
     * Chạy async trên virtual thread (emailTaskExecutor) — không chặn API trả response.
     * Email fail không ảnh hưởng đến việc đổi status (thread riêng, không trong transaction).
     *
     * @param to          Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName    Tên ứng viên
     * @param jobTitle    Tên job đã ứng tuyển
     * @param companyName Tên công ty nhà tuyển dụng
     */
    @Async("emailTaskExecutor")
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
     * Chạy async trên virtual thread (emailTaskExecutor) — không chặn API trả response.
     * Email fail không ảnh hưởng đến việc đổi status (thread riêng, không trong transaction).
     *
     * @param to             Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName       Tên ứng viên
     * @param jobTitle       Tên job đã ứng tuyển
     * @param companyName    Tên công ty nhà tuyển dụng
     * @param rejectedReason Lý do từ chối (đã validate bắt buộc ở service)
     */
    @Async("emailTaskExecutor")
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
