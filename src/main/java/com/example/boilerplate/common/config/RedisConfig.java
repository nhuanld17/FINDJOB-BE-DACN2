package com.example.boilerplate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        // Kết nối RedisTemplate với Redis server thông qua factory
        // (factory được Spring tự inject từ cấu hình application.properties)
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Serializer cho key: lưu dạng plain String "blacklist:abc123"
        // → dễ đọc khi debug trên Redis CLI
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Serializer cho value: lưu dạng JSON {"userId":1,"email":"..."}
        // → thay thế mặc định JdkSerializationRedisSerializer (binary, không đọc được)
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        // Key của opsForValue() — vd: "blacklist:token123
        template.setKeySerializer(stringSerializer);
        // Key của opsForHash() — vd: field name trong Redis Hash
        template.setHashKeySerializer(stringSerializer);

        // Value của opsForValue() — lưu object dạng JSON
        template.setValueSerializer(jsonSerializer);
        // Value của opsForHash() — lưu hash field value dạng JSON
        template.setHashValueSerializer(jsonSerializer);

        // Bắt buộc gọi sau khi set serializer để khởi tạo template
        template.afterPropertiesSet();

        return template;
    }

    /**
     * RedisTemplate riêng cho OAuth2 Authorization Request.
     *
     * <p>Dùng {@link JdkSerializationRedisSerializer} thay vì Jackson JSON vì
     * {@link OAuth2AuthorizationRequest} chứa các class như
     * {@code OAuth2AuthorizationResponseType} mà Jackson không thể
     * deserialize được (thiếu default constructor / Jackson annotation).
     *
     * <p>Vì {@code OAuth2AuthorizationRequest} implement {@link java.io.Serializable},
     * JDK serialization hoạt động ổn định và an toàn cho dữ liệu tạm thời (TTL 120s).
     *
     * <p>Dùng trong {@code RedisOAuth2AuthorizationRequestRepository}.
     */
    @Bean
    public RedisTemplate<String, OAuth2AuthorizationRequest> oauth2StateRedisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, OAuth2AuthorizationRequest> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        JdkSerializationRedisSerializer jdkSerializer = new JdkSerializationRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jdkSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jdkSerializer);

        template.afterPropertiesSet();

        return template;
    }
}