package com.example.boilerplate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {
    /**
     * Virtual Thread Pool cho các tác vụ gửi email bất đồng bộ (@Async).
     *
     * Dùng Virtual Thread (Java 21+ Project Loom) thay cho platform thread pool
     * vì gửi email là I/O-bound (chờ SMTP network), Virtual Thread xử lý I/O
     * hiệu quả hơn:
     * - Không cần corePoolSize / maxPoolSize / queueCapacity
     * - Mỗi task tạo 1 Virtual Thread riêng, xong tự giải phóng
     * - Không block OS thread khi chờ SMTP — JVM tự mount/unmount VT
     * - Chịu tải spike tốt hơn: không bị reject dù 1000 request đồng thời
     *
     * Lưu ý:
     * - Chỉ áp dụng cho emailTaskExecutor (I/O-bound), KHÔNG bật global
     * - Không dùng cho CPU-bound tasks (tính toán, mã hoá, sort...)
     * - Project an toàn: không có synchronized, không ThreadLocal phức tạp
     *
     * Cách dùng trong EmailService:
     * @Async("emailTaskExecutor")
     * public void sendOtpEmail(...) { ... }
     */
    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
