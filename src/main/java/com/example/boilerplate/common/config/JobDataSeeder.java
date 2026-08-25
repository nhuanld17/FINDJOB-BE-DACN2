package com.example.boilerplate.common.config;

import com.example.boilerplate.common.constant.City;
import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.common.constant.JobType;
import com.example.boilerplate.common.constant.Seniority;
import com.example.boilerplate.features.company.entity.Company;
import com.example.boilerplate.features.company.repository.CompanyRepository;
import com.example.boilerplate.features.job.entity.Category;
import com.example.boilerplate.features.job.entity.Job;
import com.example.boilerplate.features.job.entity.JobCategory;
import com.example.boilerplate.features.job.entity.JobCategoryId;
import com.example.boilerplate.features.job.repository.CategoryRepository;
import com.example.boilerplate.features.job.repository.JobCategoryRepository;
import com.example.boilerplate.features.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * JobDataSeeder — CommandLineRunner seed dữ liệu JOB để test (dev).
 * 
 * Chạy MỘT LẦN mỗi lần khởi động app. IDEMPOTENT: chỉ seed cho công ty nào
 * CHƯA có job nào (công ty đã có job → bỏ qua, không tạo trùng).
 * 
 * Mục đích chính: tạo đủ dữ liệu để test KEYSET PAGINATION trên màn hình
 * "Quản lý tin tuyển dụng" (GET /api/v1/jobs/manage):
 * 
 *   - Seed {@code per-company} job (mặc định 45 — lớn hơn size=20 để cuộn load nhiều trang)
 *   - Trộn đủ 4 trạng thái ACTIVE/EXPIRED/DRAFT/CLOSED → test bộ lọc status
 *   - created_at được backdate dàn đều (mỗi job cách nhau 1 giờ) → test thứ tự keyset
 *   - Gắn job_categories (lấy từ bảng categories đã seed ở V10)
 * 
 * 
 * Điều khiển bằng properties (application.yml):
 * 
 * app:
 *   seed-jobs:
 *     enabled: true        # bật/tắt seeder
 *     per-company: 45      # số job seed cho mỗi công ty chưa có job
 * 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobDataSeeder implements CommandLineRunner {

    private final CompanyRepository companyRepository;
    private final JobRepository jobRepository;
    private final CategoryRepository categoryRepository;
    private final JobCategoryRepository jobCategoryRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.seed-jobs.enabled:false}")
    private boolean enabled;

    @Value("${app.seed-jobs.per-company:45}")
    private int jobsPerCompany;

    // ---- Data mẫu (đủ đa dạng để test search + filter) ----

    private static final String[] TITLES = {
            "Senior Java Developer",
            "Frontend React Developer",
            "Backend Node.js Engineer",
            "DevOps Engineer",
            "Data Analyst",
            "Mobile React Native Developer",
            "QA Automation Engineer",
            "Fullstack Developer",
            "Product Manager",
            "UI/UX Designer",
            "System Administrator",
            "Machine Learning Engineer",
    };

    private static final String[] DESCRIPTIONS = {
            "Tham gia phát triển sản phẩm lõi của công ty, làm việc trong team agile.",
            "Xây dựng và vận hành các hệ thống phân tán, xử lý triệu request/ngày.",
            "Phối hợp cùng product owner để thiết kế và triển khai tính năng mới.",
            "Tối ưu hiệu năng hệ thống, đảm bảo uptime và khả năng scale.",
    };

    private static final String[] REQUIREMENTS = {
            "Có 2+ năm kinh nghiệm, thành thạo toolchain phù hợp với vị trí.",
            "Kinh nghiệm làm việc với database quan hệ, hiểu về caching.",
            "Có tư duy hệ thống, khả năng debug và viết unit test tốt.",
            "Tiếng Anh đọc hiểu tài liệu kỹ thuật.",
    };

    private static final String[] BENEFITS = {
            "Lương tháng 13 + thưởng hiệu quả theo quý.",
            "Môi trường trẻ trung, công nghệ mới, mentor tận tình.",
            "Bảo hiểm sức khoẻ cao cấp cho bản thân và người thân.",
            "Khám sức khoẻ định kỳ, team building hàng quý.",
    };

    private static final City[] CITIES = {
            City.HA_NOI, City.HO_CHI_MINH, City.DA_NANG, City.HAI_PHONG, City.CAN_THO, City.BINH_DUONG,
    };

    private static final Seniority[] SENIORITIES = {
            Seniority.INTERN, Seniority.FRESHER, Seniority.JUNIOR,
            Seniority.MIDDLE, Seniority.SENIOR, Seniority.LEAD, Seniority.MANAGER,
    };

    private static final JobType[] JOB_TYPES = {
            JobType.FULL_TIME, JobType.PART_TIME, JobType.CONTRACT,
            JobType.INTERNSHIP, JobType.REMOTE, JobType.HYBRID,
    };

    private static final String[] SKILLS = {
            "Java", "Spring Boot", "React", "Node.js", "TypeScript", "PostgreSQL",
            "Docker", "AWS", "Redis", "Kubernetes", "Python", "SQL",
    };

    @Override
    @Transactional
    public void run(String... args) {
//        if (!enabled) {
//            log.info("[Seeder] app.seed-jobs.enabled=false → bỏ qua seed job");
//            return;
//        }
//
//        List<Company> companies = companyRepository.findAll().stream()
//                .filter(c -> !c.isDeleted())
//                .toList();
//
//        if (companies.isEmpty()) {
//            log.warn("[Seeder] Không có công ty nào để seed job (tạo công ty trước).");
//            return;
//        }
//
//        List<Category> categories = categoryRepository.findAll();
//
//        int totalSeeded = 0;
//        for (Company company : companies) {
//            // Idempotent: công ty đã có job → bỏ qua (tránh seed trùng mỗi lần start)
//            long existing = jobRepository.countByCompanyIdAndDeletedFalse(company.getId());
//            if (existing > 0) {
//                log.info("[Seeder] Công ty '{}' đã có {} job → bỏ qua", company.getName(), existing);
//                continue;
//            }
//            totalSeeded += seedForCompany(company, categories);
//        }
//
//        log.info("[Seeder] ✅ Hoàn tất — đã seed {} job mới", totalSeeded);
    }

    /**
     * Seed {@code jobsPerCompany} job cho 1 công ty.
     *
     * @return số job đã tạo
     */
    private int seedForCompany(Company company, List<Category> categories) {
        int count = 0;
        for (int i = 0; i < jobsPerCompany; i++) {
            Job job = buildJob(company, i);
            jobRepository.save(job);   // flush để có job.getId() (IDENTITY) cho JobCategory

            // Gắn 1-2 danh mục (categories seeded ở V10).
            // Category thứ 2 dùng (i+1) thay vì (i*7) — tránh trùng category thứ 1
            // khi (i*7) % size == i % size (vd i=0,5,10... với size=30) → composite
            // PK (job_id, category_id) bị trùng.
            if (!categories.isEmpty()) {
                attachCategory(job, categories.get(i % categories.size()));
                if (i % 2 == 1) {
                    attachCategory(job, categories.get((i + 1) % categories.size()));
                }
            }

            // Backdate created_at → mỗi job cách nhau 1 giờ, job cũ nhất seed trước.
            // Mục đích: test keyset pagination (ORDER BY created_at DESC, id DESC)
            // có thứ tự rõ ràng thay vì tất cả cùng giờ.
            int hoursAgo = jobsPerCompany - i;
            jdbcTemplate.update(
                    "UPDATE jobs SET created_at = now() - (? * interval '1 hour') WHERE id = ?",
                    hoursAgo, job.getId());

            count++;
        }
        log.info("[Seeder] Seeded {} job cho công ty '{}'", count, company.getName());
        return count;
    }

    /** Build 1 Job với dữ liệu đa dạng theo index i (trạng thái, lương, thành phố, ...). */
    private Job buildJob(Company company, int i) {
        Job job = new Job();

        String title = TITLES[i % TITLES.length];
        job.setCompany(company);
        job.setCreatedBy(company.getOwner());
        job.setTitle(title);
        job.setSlug(slugify(title) + "-" + company.getId() + "-" + i); // slug unique trong company

        job.setDescription(DESCRIPTIONS[i % DESCRIPTIONS.length]);
        job.setRequirements(REQUIREMENTS[i % REQUIREMENTS.length]);
        job.setBenefits(BENEFITS[i % BENEFITS.length]);

        // Lương: 5tr -> 45tr, xen kẽ
        long salaryUnit = 5_000_000L + (i % 8) * 5_000_000L;
        job.setSalaryMin(BigDecimal.valueOf(salaryUnit));
        job.setSalaryMax(BigDecimal.valueOf(salaryUnit + 10_000_000L));
        job.setSalaryCurrency("VND");

        job.setYearsOfExperience((i % 7) + 1);
        job.setSeniority(SENIORITIES[i % SENIORITIES.length]);
        job.setJobType(JOB_TYPES[i % JOB_TYPES.length]);
        job.setLocation(CITIES[i % CITIES.length].getDisplayName());
        job.setCity(CITIES[i % CITIES.length]);

        // Skills: 3 kỹ năng lấy vòng tròn (không trùng nhau)
        int base = i % SKILLS.length;
        job.setSkillsRequired(List.of(SKILLS[base], SKILLS[(base + 3) % SKILLS.length], SKILLS[(base + 6) % SKILLS.length]));

        // Trạng thái: trộn 4 loại (60% ACTIVE, 10% EXPIRED, 20% DRAFT, 10% CLOSED)
        JobStatus status = switch (i % 10) {
            case 6 -> JobStatus.EXPIRED;
            case 7, 8 -> JobStatus.DRAFT;
            case 9 -> JobStatus.CLOSED;
            default -> JobStatus.ACTIVE;
        };
        job.setStatus(status);

        // expiryDate: ACTIVE/DRAFT/CLOSED → tương lai; EXPIRED → quá khứ
        job.setExpiryDate(status == JobStatus.EXPIRED
                ? LocalDate.now().minusDays((i % 10) + 1)
                : LocalDate.now().plusDays((i % 40) + 15));

        job.setApplyCount(0);   // số ứng tuyển THẬT — job seed chưa ai apply nên = 0
        return job;
    }

    /** Gắn 1 danh mục cho job (bảng job_categories). */
    private void attachCategory(Job job, Category category) {
        JobCategory jc = new JobCategory();
        jc.setId(new JobCategoryId(job.getId(), category.getId()));
        jc.setJob(job);
        jc.setCategory(category);
        jobCategoryRepository.save(jc);
    }

    /** Bỏ dấu tiếng Việt + chuẩn hoá thành slug (tương tự JobService.slugify). */
    private String slugify(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return normalized
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
