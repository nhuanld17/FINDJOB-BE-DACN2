package com.example.boilerplate.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HttpMailSender implements MailSender {

    private final MailHttpProperties props;

    /**
     * RestClient build MỘT LẦN duy nhất — tái sử dụng connection pool + TLS session
     * qua các lần gửi. Trước đây build mới từng email → mỗi mail phải TCP connect
     * + TLS handshake lại từ đầu tới api.brevo.com (tốn vài trăm ms/mail).
     * RestClient bên dưới dùng connection pool của JDK HttpClient nên an toàn khi
     * gọi đồng thời từ nhiều worker thread.
     */
    private final RestClient client;

    public HttpMailSender(MailHttpProperties props) {
        this.props = props;
        this.client = RestClient.builder()
                .baseUrl(props.getApiUrl())
                .defaultHeader("api-key", props.getApiKey()) // header xác thực Brevo (không phải Bearer)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        Map<String, Object> sender = Map.of(
                "name", props.getSenderName(),
                "email", props.getSenderEmail()
        );

        Map<String, Object> recipient = Map.of("email", to);

        Map<String, Object> body = Map.of(
                "sender", sender,
                "to", List.of(recipient),
                "subject", subject,
                "htmlContent", htmlContent
        );

        int attempts = 0;
        long backoff = props.getRetryBaseMs();
        long startNs = System.nanoTime();

        while (true) {
            attempts++;
            try {
                client.post().body(body).retrieve().toBodilessEntity();

                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                log.info("[BREVO] OK to={} total={}ms (attempt={})", to, elapsedMs, attempts);
                return;
            } catch (RestClientResponseException e) {

                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;

                if (!retryable || attempts >= props.getMaxRetries()) {
                    throw new RuntimeException("Brevo send failed (HTTP " + status + ") to: " + to, e);
                }

                long wait = e.getResponseHeaders().getFirst("x-sib-ratelimit-reset") != null
                        ? Long.parseLong(e.getResponseHeaders().getFirst("x-sib-ratelimit-reset")) * 1000L
                        : backoff;
                log.warn("Brevo HTTP {} — retry {}/{} sau {}ms, to={}", status, attempts,
                        props.getMaxRetries(), wait, to);
                sleep(wait);
                backoff *= 2;
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while retrying Brevo send", ie);
        }
    }
}
