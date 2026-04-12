package com.example.boilerplate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /*
     * Cấu hình CORS (Cross-Origin Resource Sharing) cho toàn bộ API.
     *
     * CORS là gì?
     * - Browser có cơ chế bảo mật chặn request từ origin A đến origin B
     *   nếu server B không cho phép rõ ràng.
     * - Ví dụ: frontend chạy ở localhost:3000, backend ở localhost:8080
     *   → browser sẽ chặn nếu không có CORS config.
     *
     * allowedOrigins:
     * - Danh sách origin được phép gọi API.
     * - Thêm domain production vào đây trước khi deploy lên server.
     * - KHÔNG dùng "*" (wildcard) khi allowCredentials = true vì Spring
     *   sẽ throw exception. Phải liệt kê rõ từng origin.
     *
     * allowedMethods:
     * - Các HTTP method được chấp nhận từ cross-origin request.
     * - OPTIONS bắt buộc phải có: browser luôn gửi OPTIONS preflight
     *   trước khi gửi request thật để hỏi server "tôi có được gọi không?".
     *   Nếu thiếu OPTIONS, mọi POST/PUT đều bị chặn ở bước preflight.
     *
     * allowedHeaders = "*":
     * - Chấp nhận mọi request header, bao gồm Authorization, Content-Type
     *   và các custom header khác.
     *
     * allowCredentials = true:
     * - BẮT BUỘC phải bật vì flow OTP dùng cookie PENDING_TOKEN (HttpOnly).
     * - Nếu để false, browser sẽ không đính kèm cookie vào request
     *   → PENDING_TOKEN không được gửi lên → toàn bộ flow OTP bị vỡ.
     * - Khi bật, frontend cũng phải set credentials: "include" trong fetch/axios.
     *
     * registerCorsConfiguration("/api/**"):
     * - Chỉ áp dụng CORS cho các endpoint bắt đầu bằng /api/.
     * - Các path khác (actuator, static...) không bị ảnh hưởng.
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000")); // FE dev
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true); // bắt buộc vì dùng Cookie PENDING_TOKEN
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
