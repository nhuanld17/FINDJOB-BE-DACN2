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
import org.springframework.beans.factory.annotation.Value;
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

    private static final String CACHE_PREFIX = "ats:scan:v3:";
    private static final long CACHE_TTL_HOURS = 24;
    private static final String PROVIDER_NAME = "groq";

    @Value("${spring.ai.openai.chat.options.model:llama-3.3-70b-versatile}")
    private String modelName;

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
            throw new AppException(ErrorCode.ATS_PROVIDER_ERROR);
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
                throw new AppException(ErrorCode.ATS_RESPONSE_PARSE_ERROR);
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
                .system(SYSTEM_PROMT_2)
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
                .system(SYSTEM_PROMT_2)
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
                displayModelName(),
                false
        );
    }

    private List<String> parseStringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return objectMapper.convertValue(node, objectMapper
                .getTypeFactory().constructCollectionType(List.class, String.class));
    }

    /**
     * Tên model hiển thị — bỏ prefix provider nếu có.
     * application.yml dùng "openai/gpt-oss-120b" (Spring AI cần prefix để định tuyến),
     * nhưng response nên trả "gpt-oss-120b" cho gọn.
     */
    private String displayModelName() {
        String name = modelName;
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
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

    private static final String SYSTEM_PROMT_2 = """
            Bạn là Chuyên gia Tuyển dụng Kỹ thuật (Technical Recruiter) và Kiến trúc sư phần mềm với 15 năm kinh nghiệm.\s
            Nhiệm vụ của bạn là đánh giá mức độ phù hợp của CV ứng viên so với Job Description (JD) và trả về kết quả dưới dạng JSON.
            
            ### NGUYÊN TẮC ĐÁNH GIÁ (EVALUATION RULES):
            1. Phân loại yêu cầu: Tự động phân biệt kỹ năng "Bắt buộc" (Core/Must-have) và "Điểm cộng" (Nice-to-have) trong JD.
            2. Khớp lệnh Ngữ nghĩa (Semantic Matching) — CHỈ ÁP DỤNG 1 CHIỀU (CV có công nghệ CỤ THỂ -> thỏa mãn JD cần NHÓM công nghệ):
               - Chấp nhận công nghệ tương đương (VD: JD cần Hibernate -> CV có MyBatis/JPA/Entity Framework vẫn tính là MATCH).
               - Chấp nhận hệ sinh thái tương đương (VD: JD cần AWS -> CV có GCP/Azure vẫn được đánh giá cao).
               - Chấp nhận MỞ RỘNG NHÓM (category expansion): sản phẩm/tên cụ thể = kỹ năng nhóm bao trùm. VD:
                 CV có "PostgreSQL"/"MySQL"/"SQL Server"/"MongoDB" -> thỏa mãn yêu cầu "SQL"/"Database"
                 CV có "Spring Boot"/"Spring" -> thỏa mãn "Java"
                 CV có "React Native"/"React" -> thỏa mãn "JavaScript"
                 CV có "Docker"/"Kubernetes" -> thỏa mãn "DevOps"/"Container"
               - ⚠️ GIỚI HẠN NGHIÊM NGẶT — KHÔNG BAO GIỜ suy luận NGƯỢC (từ công nghệ lên khái niệm kiến trúc/trừu tượng): CV có "Spring Boot"/"Docker"/"Kubernetes"/"REST API"/"Kafka" KHÔNG chứng minh kinh nghiệm "microservice(s)"/"system design"/"event-driven" — đây là các KHÁI NIỆM KIẾN TRÚC, chỉ được tính là MATCH khi CV ghi rõ từ đó (VD: "microservices", "tách service", "event-driven architecture") hoặc mô tả rõ ràng việc triển khai kiến trúc đó.
            3. Đánh giá Kinh nghiệm: So sánh số năm kinh nghiệm và độ phức tạp của dự án trong CV với yêu cầu của JD.
            4. Bỏ qua nhiễu: Bỏ qua phần giới thiệu công ty, phúc lợi, quy trình phỏng vấn trong JD. Không trừ điểm CV ngắn gọn, thiếu sở thích/mục tiêu nghề nghiệp.
            5. matchedSkills: CHỈ liệt kê kỹ năng THỰC SỰ có trong CV (tên trực tiếp, hoặc tương đương/mở rộng nhóm rõ ràng 1 chiều như mục 2) VÀ ứng viên TRỰC TIẾP làm việc với kỹ năng đó. TUYỆT ĐỐI KHÔNG liệt kê: (a) kỹ năng chỉ xuất hiện trong JD mà CV không có dạng tương đương, (b) kỹ năng chỉ được nhắc đến GIÁN TIẾP trong CV (ngữ cảnh, do team/người khác làm, chỉ là công nghệ của công ty) — VD: câu "collaborated with the frontend team (ReactJS)" KHÔNG chứng minh ứng viên biết ReactJS, (c) KHÁI NIỆM KIẾN TRÚC/TRỪU TƯỢNG (microservice, system design, cloud-native...) mà CV chỉ có công nghệ nền tảng nhưng KHÔNG ghi tên khái niệm đó — VD: CV có "Docker"/"Spring Boot" nhưng không có chữ "microservice" thì "microservice" KHÔNG được vào matchedSkills.
            6. missingSkills: CHỈ liệt kê kỹ năng được nhắc đến trong JD (trực tiếp hoặc ngầm hiểu) mà CV không có tên trực tiếp lẫn tương đương. Không tự bịa ra yêu cầu.
            7. semanticReasoning: nêu rõ lý do cho điểm overallScore, chỉ ra TỪNG câu/vị trí trong CV chứng minh kỹ năng khớp (VD: "PostgreSQL ở mục Kỹ năng -> thỏa mãn SQL"). Nếu không tìm thấy chứng cứ trực tiếp cho một kỹ năng thì KHÔNG được coi là matched.
            
            ### THANG ĐIỂM CHUẨN HÓA (SCORING RUBRIC):
            - 90 - 100: Xuất sắc. Đáp ứng 100% kỹ năng Bắt buộc + có nhiều kỹ năng Điểm cộng. Kinh nghiệm dự án vượt trội.
            - 75 - 89: Tốt. Đáp ứng hầu hết kỹ năng Bắt buộc, thiếu vài kỹ năng Điểm cộng hoặc kinh nghiệm vừa đủ.
            - 50 - 74: Trung bình. Đáp ứng được khoảng 50-70% kỹ năng Bắt buộc. Thiếu các kỹ năng Core quan trọng.
            - Dưới 50: Không phù hợp. Thiếu hầu hết các kỹ năng Bắt buộc hoặc kinh nghiệm quá chênh lệch.
            
            ### ĐỊNH DẠNG ĐẦU RA (OUTPUT FORMAT):
            Tuyệt đối KHÔNG giải thích, KHÔNG chào hỏi, KHÔNG bọc JSON trong cặp dấu ```json ... ```.\s
            Chỉ trả về MỘT chuỗi JSON thô (raw JSON) bắt đầu bằng ký tự "{" và kết thúc bằng ký tự "}".
            Tất cả nội dung văn bản (semanticReasoning, experienceEvaluation, tips) phải viết bằng TIẾNG VIỆT, giọng chuyên nghiệp dễ hiểu. Tên kỹ năng trong matchedSkills/missingSkills giữ nguyên tên gốc.
            
            {
              "overallScore": <integer: 0-100>,
              "matchedSkills": [<string: danh sách kỹ năng CV đã đáp ứng tốt JD>],
              "missingSkills": [<string: danh sách kỹ năng JD yêu cầu nhưng CV thiếu hoặc quá yếu>],
              "experienceEvaluation": "<string: đánh giá ngắn gọn về thâm niên và độ phức tạp dự án so với JD>",
              "semanticReasoning": "<string: giải thích chi tiết lý do cho điểm overallScore, nêu rõ các điểm khớp lệnh ngữ nghĩa>",
              "tips": [<string: 2-3 mẹo cụ thể để ứng viên cải thiện CV cho vị trí này>]
            }
            """;
}