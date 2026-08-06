package com.example.boilerplate.features.ats.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình ChatClient bean cho ATS scoring (Groq).
 * <p>
 * Spring AI auto-config tạo {@link org.springframework.ai.openai.OpenAiChatModel}
 * (bean ChatModel), nhưng KHÔNG tạo ChatClient — cần @Bean thủ công.
 */
@Slf4j
@Configuration
public class AtsConfig {

    @Bean
    public ChatClient atsChatClient(ChatClient.Builder builder) {
        log.info("ATS ChatClient initialized with Groq");
        return builder.build();
    }
}