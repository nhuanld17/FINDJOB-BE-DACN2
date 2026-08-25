package com.example.boilerplate.common.config;

import com.cloudinary.Cloudinary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Cấu hình Cloudinary SDK dùng để upload file (logo, cover, avatar, ...).
 *
 * Thông tin lấy từ env:
 * 
 *   - {@code CLOUDINARY_CLOUD_NAME} — tên cloud trên Cloudinary
 *   - {@code CLOUDINARY_API_KEY} — API Key từ Dashboard
 *   - {@code CLOUDINARY_API_SECRET} — API Secret từ Dashboard
 * 
 *
 * Usage:
 *   @Autowired
 *   private Cloudinary cloudinary;
 *
 *   Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.emptyMap());
 *   String url = result.get("url").toString();
 */
@Configuration
public class CloudinaryConfig {

    @Value("${cloudinary.cloud-name}")
    private String cloudName;

    @Value("${cloudinary.api-key}")
    private String apiKey;

    @Value("${cloudinary.api-secret}")
    private String apiSecret;

    @Bean
    public Cloudinary cloudinary() {
        Map<String, String> config = new HashMap<>();
        config.put("cloud_name", cloudName);
        config.put("api_key", apiKey);
        config.put("api_secret", apiSecret);
        return new Cloudinary(config);
    }
}
