package com.example.boilerplate.infrastructure.mail;

public interface MailSender {
    void sendHtmlEmail(String to, String subject, String htmlContent);
}
