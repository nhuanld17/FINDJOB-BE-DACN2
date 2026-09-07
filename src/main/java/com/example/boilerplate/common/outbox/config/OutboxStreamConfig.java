package com.example.boilerplate.common.outbox.config;

import com.example.boilerplate.common.outbox.consumer.EventStreamConsumer;
import io.lettuce.core.RedisBusyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class OutboxStreamConfig {

    /**
     * Tên consumer của instance hiện tại: hostname + timestamp base36,
     * đảm bảo duy nhất giữa các instance. Dùng để đăng kí consumer trong group và
     * để PendingReclaimer claim lại message.
     */
    public static final String CONSUMER_NAME = buildConsumerName();
    private final StringRedisTemplate redisTemplate;
    private final ApplicationContext applicationContext;   // dùng để lấy OutboxWorkerManager
    private final OutboxStreamProperties outboxStreamProperties;

    private static String buildConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }

        return host + ":" + Long.toString(System.currentTimeMillis(), 36);
    }

    /**
     * Bean quản lý vòng đời N worker poll của outbox.
     *
     * Mỗi worker = 1 task chạy EventStreamConsumer.pollLoop(workerName) trên
     * 1 thread riêng, tự gọi XREADGROUP COUNT=N rồi xử lí lô.
     * Bean không tự nộp task: phải gọi start() (từ onAppReady, SAU khi XGROUP
     * CREATE) thì worker mới bắt đầu — như vậy worker không bao giờ XREADGROUP
     * trước khi group tồn tại (tránh NOGROUP race lúc startup).
     * destroyMethod = "stop" → Spring gọi stop() khi app shutdown.
     */
    @Bean(destroyMethod = "stop")
    OutboxWorkerManager outboxWorkerManager(EventStreamConsumer consumer,
                                            OutboxStreamProperties props) {
        return new OutboxWorkerManager(consumer, props);
    }

    /**
     * Chạy SAU KHI app ready (ApplicationReadyEvent).
     *
     * Thứ tự thực thi:
     * 1. Đảm bảo stream key tồn tại (XINFO STREAM, nếu chưa có thì XADD dummy)
     * 2. Tạo consumer group (nếu đã có thì BUSYGROUP → bỏ qua)
     * 2b. Xóa dummy entry nếu vừa tạo (consumer đọc phải thì crash)
     * 3. Start worker SAU KHI group đã tồn tại
     *
     * Nếu stream bị xóa giữa 2 lần chạy, XREADGROUP sẽ fail NOGROUP —
     * kiểm tra stream key TRƯỚC khi tạo group.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        String streamKey = outboxStreamProperties.streamKey();
        String consumerGroup = outboxStreamProperties.consumerGroup();

        // 1. Đảm bảo stream key tồn tại
        RecordId dummyId = null;
        try {
            redisTemplate.opsForStream().info(streamKey);
        } catch (RedisSystemException e) {
            dummyId = redisTemplate.opsForStream().add(
                    StreamRecords.string(Map.of("_", "_")).withStreamKey(streamKey));
            log.info("Stream '{}' created (was missing)", streamKey);
        }

        // 2. Tạo consumer group (nếu đã có thì BUSYGROUP → bỏ qua)
        try {
            redisTemplate.opsForStream().createGroup(
                    streamKey, ReadOffset.from("0"), consumerGroup);
            log.info("Consumer group '{}' created on stream '{}'",
                    consumerGroup, streamKey);
        } catch (RedisSystemException ex) {
            if (ex.getCause() instanceof RedisBusyException) {
                log.info("Consumer group '{}' already exists", consumerGroup);
            } else {
                throw ex;
            }
        }

        // 2b. Xóa dummy entry nếu vừa tạo
        if (dummyId != null) {
            redisTemplate.opsForStream().delete(streamKey, dummyId);
        }

        // 3. Bật worker sau khi stream + group đã chắc chắn tồn tại
        applicationContext.getBean(OutboxWorkerManager.class).start();
        log.info("Outbox workers started");
    }

    /**
     * Quản lý N worker poll: executor chạy pollLoop, start() nộp task sau khi
     * group đã tạo, stop() tắt cờ + shutdown khi app dừng.
     */
    static class OutboxWorkerManager {
        private final EventStreamConsumer consumer;
        private final OutboxStreamProperties props;
        private final ThreadPoolTaskExecutor executor;

        OutboxWorkerManager(EventStreamConsumer consumer, OutboxStreamProperties props) {
            this.consumer = consumer;
            this.props = props;
            int workers = props.workers();
            executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(workers);
            executor.setMaxPoolSize(workers);
            executor.setQueueCapacity(0);               // không xếp hàng: N worker = N task sống mãi
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            executor.setThreadNamePrefix("outbox-worker-");
            executor.initialize();
        }

        /** Gọi từ onAppReady — SAU khi XGROUP CREATE xong. Idempotent. */
        public synchronized void start() {
            if (consumer.isRunning()) {
                return;
            }
            consumer.setRunning(true);                  // pollLoop bắt đầu vào vòng while
            for (int i = 0; i < props.workers(); i++) {
                String workerName = CONSUMER_NAME + "-w" + i;
                executor.execute(() -> {
                    try {
                        consumer.pollLoop(workerName);
                    } catch (Throwable t) {
                        // pollLoop đã tự try-catch bên trong — tới đây là bất khả kháng
                        log.error("Outbox worker {} chết", workerName, t);
                    }
                });
            }
            log.info("Started {} outbox worker(s)", props.workers());
        }

        /** Spring gọi khi app shutdown (destroyMethod). */
        public void stop() {
            consumer.setRunning(false);                 // pollLoop thoát sau lượt XREADGROUP block
            executor.shutdown();                        // không nhận task mới
            try {
                // Worker đang block XREADGROUP tối đa pollTimeoutMs mới thấy cờ tắt,
                // + thời gian xử lí lô đang dở. 30s đủ cho mặc định (2s + vài ms/lô);
                // nếu SMTP chậm mà timeout bị chạm → giãn con số này.
                // ThreadPoolTaskExecutor không expose awaitTermination/shutdownNow
                // → đi qua ThreadPoolExecutor bên dưới
                if (!executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Outbox workers chưa dừng sau 30s, shutdownNow");
                    executor.getThreadPoolExecutor().shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
