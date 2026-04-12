package com.example.boilerplate.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.builder().build();

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
}
