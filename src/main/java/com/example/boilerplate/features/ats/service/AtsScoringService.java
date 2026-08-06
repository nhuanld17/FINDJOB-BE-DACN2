package com.example.boilerplate.features.ats.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import com.example.boilerplate.features.ats.dto.AtsResultDto;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.service.JobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service chấm CV bằng AI (Groq qua Spring AI OpenAI starter).
 * <p>
 * Flow:
 * <ol>
 *   <li>Nhận CV text + JD text (từ jobId hoặc tự paste)</li>
 *   <li>Check Redis cache: hash(cvText + jdText) → nếu có → trả luôn</li>
 *   <li>Gọi Groq LLM với prompt để chấm CV</li>
 *   <li>Parse JSON response → lưu cache → trả về</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AtsScoringService {

    private final ChatClient chatClient;
    private final JobService jobService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "ats:scan:";
    private static final long CACHE_TTL_HOURS = 24;
    private static final String MODEL_NAME = "llama-3.3-70b-versatile";
    private static final String PROVIDER_NAME = "groq";

    /**
     * Chấm CV dựa trên JD (lấy từ jobId hoặc jdText).
     */
    public AtsResultDto score(String cvText, Long jobId, String jdText) {
        // Lấy JD từ jobId hoặc dùng jdText
        String jobDescription;
        if (jobId != null) {
            Job job = jobService.getJobEntityById(jobId);
            jobDescription = buildJdText(job);
        } else if (jdText != null && !jdText.isBlank()) {
            jobDescription = jdText.trim();
        } else {
            throw new AppException(ErrorCode.ATS_MISSING_INPUT);
        }

        // Giới hạn JD dài
        if (jobDescription.length() > 3000) {
            jobDescription = jobDescription.substring(0, 3000);
        }

        // Check cache
        String cacheKey = buildCacheKey(cvText, jobDescription);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                AtsResultDto cachedResult = objectMapper.readValue(cached, AtsResultDto.class);
                log.info("ATS cache HIT for key={}", cacheKey);
                return new AtsResultDto(
                        cachedResult.overallScore(),
                        cachedResult.matchedSkills(),
                        cachedResult.missingSkills(),
                        cachedResult.semanticReasoning(),
                        cachedResult.tips(),
                        cachedResult.cvTextLength(),
                        cachedResult.provider(),
                        cachedResult.model(),
                        true
                );
            } catch (JsonProcessingException e) {
                log.warn("ATS cache parse error, will re-score: {}", e.getMessage());
                redisTemplate.delete(cacheKey);
            }
        }

        // Gọi Groq
        String llmResponse;
        try {
            llmResponse = callGroq(cvText, jobDescription);
        } catch (Exception e) {
            log.error("ATS Groq call failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.ATS_PROVIDER_ERROR,
                    "AI scoring service is temporarily unavailable, please try again later");
        }

        // Parse JSON — retry 1 lần nếu fail
        AtsResultDto result;
        try {
            result = parseResult(llmResponse, cvText.length());
        } catch (Exception e) {
            log.warn("ATS JSON parse failed (first attempt), retrying: {}", e.getMessage());
            try {
                llmResponse = callGroqRetry(cvText, jobDescription);
                result = parseResult(llmResponse, cvText.length());
            } catch (Exception e2) {
                log.error("ATS JSON parse failed after retry: {}", e2.getMessage(), e2);
                throw new AppException(ErrorCode.INTERNAL_ERROR,
                        "AI response could not be parsed. Please try again.");
            }
        }

        // Lưu cache
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(result),
                    CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("ATS cache write failed: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Gọi Groq chat completions với prompt.
     */
    private String callGroq(String cvText, String jdText) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("=== CV TEXT ===\n" + cvText + "\n\n=== JOB DESCRIPTION ===\n" + jdText
                        + "\n\nHãy chấm CV này.")
                .call()
                .content();
    }

    /**
     * Retry khi JSON parse fail — thêm instruction rõ ràng hơn.
     */
    private String callGroqRetry(String cvText, String jdText) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("=== CV TEXT ===\n" + cvText + "\n\n=== JOB DESCRIPTION ===\n" + jdText
                        + "\n\nCHỈ trả JSON thuần, không markdown, không code block, không giải thích thêm. Hãy chấm CV này.")
                .call()
                .content();
    }

    /**
     * Parse JSON từ LLM response thành AtsResultDto.
     */
    private AtsResultDto parseResult(String llmResponse, int cvTextLength)
            throws JsonProcessingException {
        // LLM có thể trả JSON trong ```json ... ``` block
        String json = llmResponse;
        if (json.contains("```")) {
            json = json.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
        }

        var node = objectMapper.readTree(json);

        return new AtsResultDto(
                node.get("overallScore").asInt(),
                parseStringList(node.get("matchedSkills")),
                parseStringList(node.get("missingSkills")),
                node.has("semanticReasoning") ? node.get("semanticReasoning").asText("") : "",
                parseStringList(node.get("tips")),
                cvTextLength,
                PROVIDER_NAME,
                MODEL_NAME,
                false
        );
    }

    private List<String> parseStringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return objectMapper.convertValue(node, objectMapper
                .getTypeFactory().constructCollectionType(List.class, String.class));
    }

    /**
     * Build JD text từ Job entity.
     */
    private String buildJdText(Job job) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(job.getTitle()).append("\n");
        sb.append("Description: ").append(job.getDescription()).append("\n");
        if (job.getRequirements() != null) {
            sb.append("Requirements: ").append(job.getRequirements()).append("\n");
        }
        if (job.getBenefits() != null) {
            sb.append("Benefits: ").append(job.getBenefits()).append("\n");
        }
        if (job.getSeniority() != null) {
            sb.append("Seniority: ").append(job.getSeniority().name()).append("\n");
        }
        if (job.getSkillsRequired() != null && !job.getSkillsRequired().isEmpty()) {
            sb.append("Skills: ").append(String.join(", ", job.getSkillsRequired())).append("\n");
        }
        if (job.getYearsOfExperience() != null) {
            sb.append("Years of experience: ").append(job.getYearsOfExperience()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Build cache key = SHA-256 hash của cvText + jdText.
     */
    private String buildCacheKey(String cvText, String jdText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(cvText.getBytes());
            md.update(jdText.getBytes());
            String hash = HexFormat.of().formatHex(md.digest());
            return CACHE_PREFIX + hash;
        } catch (NoSuchAlgorithmException e) {
            return CACHE_PREFIX + System.nanoTime();
        }
    }

    // =================================================================
    //  PROMPT
    // =================================================================

    private static final String SYSTEM_PROMPT = """
            Bạn là chuyên gia tuyển dụng kỹ thuật với 15 năm kinh nghiệm.
            Nhiệm vụ: chấm CV ứng viên dựa trên Job Description (JD) với thang điểm 0-100.

            Nguyên tắc chấm:
            - Kỹ năng tương đương: VD JD cần "Hibernate" → CV có "MyBatis/JPA" vẫn MATCH
            - Bỏ qua phần giới thiệu công ty, phúc lợi, chế độ đãi ngộ trong JD — chỉ chấm dựa trên yêu cầu kỹ thuật thực tế
            - Không trừ điểm vì CV ngắn gọn — ưu tiên chất lượng hơn số lượng
            - Không trừ điểm vì thiếu phần không phải kỹ thuật (sở thích, mục tiêu nghề nghiệp)

            Luôn trả lời bằng Tiếng Việt, giọng chuyên nghiệp nhưng dễ hiểu.
            Trả về JSON theo schema bên dưới, KHÔNG thêm markdown hay giải thích ngoài JSON:

            {
              "overallScore": 0-100,
              "matchedSkills": ["skill1", "skill2"],
              "missingSkills": ["skill3", "skill4"],
              "semanticReasoning": "lý do chấm điểm cụ thể, so sánh kỹ năng tương đương",
              "tips": ["mẹo cải thiện 1", "mẹo cải thiện 2"]
            }
            """;
}