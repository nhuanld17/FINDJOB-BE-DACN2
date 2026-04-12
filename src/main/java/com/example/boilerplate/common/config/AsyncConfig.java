package com.example.boilerplate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {
    /*
     * Thread pool riêng cho các tác vụ gửi email bất đồng bộ (@Async).
     *
     * Tại sao cần thread pool riêng?
     * - Spring có một thread pool mặc định dùng chung cho tất cả @Async.
     * - Nếu không khai báo riêng, EmailService sẽ dùng chung pool đó với
     *   các tác vụ async khác → email nặng có thể chiếm hết thread,
     *   làm các tác vụ khác bị treo.
     * - Tách ra giúp email có hàng đợi và giới hạn tài nguyên độc lập.
     *
     * Các thông số:
     * - corePoolSize = 2    : luôn duy trì 2 thread sẵn sàng dù không có
     *                         việc, đủ cho lượng email thông thường (OTP,
     *                         welcome mail).
     * - maxPoolSize = 5     : khi queue bắt đầu đầy, Spring sẽ tạo thêm
     *                         thread tối đa đến 5. Đủ xử lý spike nhỏ mà
     *                         không tốn quá nhiều tài nguyên.
     * - queueCapacity = 100 : tối đa 100 tác vụ được xếp hàng chờ trước
     *                         khi bị từ chối. Với app OTP thông thường,
     *                         con số này rất khó bị chạm tới.
     * - threadNamePrefix    : đặt tên thread thành "email-1", "email-2"...
     *                         giúp dễ đọc log và trace khi debug.
     *
     * Cách dùng trong EmailService:
     * @Async("emailTaskExecutor")
     * public void sendOtpEmail(...) { ... }
     */
    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("emailTaskExecutor-");
        executor.initialize();
        return executor;
    }
}
