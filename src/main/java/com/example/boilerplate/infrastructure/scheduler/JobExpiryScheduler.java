package com.example.boilerplate.infrastructure.scheduler;

import com.example.boilerplate.common.constant.JobStatus;
import com.example.boilerplate.features.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Scheduler tự động cập nhật trạng thái job khi hết hạn.
 * 
 * Chạy mỗi ngày vào lúc 00:00 (nửa đêm) để tìm tất cả job đang ACTIVE
 * nhưng đã quá {@code expiryDate} và đổi status thành {@link JobStatus#EXPIRED}.
 * 
 * Việc này đảm bảo dữ liệu trong DB luôn chính xác, không cần dựa vào
 * filter runtime ở mỗi query (dù các API search/list cũng đã filter {@code expiryDate} riêng).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobExpiryScheduler {

    private final JobRepository jobRepository;

    /**
     * Auto-expire jobs mỗi ngày lúc 00:00.
     * 
     * Cron expression: {@code 0 0 0 * * ?} = chạy vào 00:00:00 mỗi ngày.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void autoExpireJobs() {
        LocalDate today = LocalDate.now();

        var expiredJobs = jobRepository.findByStatusAndExpiryDateBefore(JobStatus.ACTIVE, today);

        if (expiredJobs.isEmpty()) {
            log.debug("No expired jobs found to auto-expire");
            return;
        }

        expiredJobs.forEach(job -> job.setStatus(JobStatus.EXPIRED));
        jobRepository.saveAll(expiredJobs);

        log.info("Auto-expired {} jobs (status ACTIVE -> EXPIRED)", expiredJobs.size());
    }
}
