package com.example.boilerplate.infrastructure.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mail.http")
public class MailHttpProperties {
    private String apiUrl;              // https://api.brevo.com/v3/smtp/email
    private String apiKey;              // BREVO_API_KEY từ .env
    private String senderEmail;         // địa chỉ người gửi (đã verify trên Brevo)
    private String senderName;          // tên hiển thị
    private int maxRetries = 3;         // số lần retry 429/5xx
    private long retryBaseMs = 500;     // backoff: 500ms -> 1s -> 2s
}
