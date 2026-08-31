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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;


import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Cấu hình phía CONSUME: tạo consumer group và container lắng nghe stream.
 *
 * Khởi tạo consumer group và StreamMessageListenerContainer.
 * Consumer group cho phép nhiều instance cùng đọc stream, mỗi message chỉ
 * được một consumer nhận.
 * Container đăng ký NHIỀU consumer (8 worker) trong cùng group để xử lý song song —
 * Redis phân phối message round-robin cho từng consumer, mỗi consumer gọi onMessage()
 * độc lập trên thread riêng.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OutboxStreamConfig {

    /**
     * Tên consumer của instance hiện tại.
     * Được tạo từ hostname + timestamp base36, đảm bảo duy nhất giữa các instance.
     * Dùng để đăng ký consumer trong group và để PendingReclaimer claim lại message.
     */
    public static final String CONSUMER_NAME = buildConsumerName();

    private final OutboxStreamProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationContext applicationContext;

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
     * Tạo container lắng nghe stream với chế độ manual ACK.
     * Sử dụng nhiều consumer trong cùng group để xử lý song song.
     * Mỗi consumer có tên riêng, Redis phân phối message round-robin.
     *
     * @Bean(destroyMethod = "stop") đảm bảo container stop khi app shutdown
     */
    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
        RedisConnectionFactory redisConnectionFactory,
        EventStreamConsumer consumer
    ) {
        // Số lượng consumer song song — nên bằng số core hoặc 8 để tối ưu
        int workers = 8;

        // Executor pool: mỗi consumer chạy trên thread riêng → xử lý song song thật.
        // Dùng fixed thread pool (KHÔNG Virtual Thread) vì consumer gọi Java Mail
        // (dùng synchronized → Virtual Thread bị pinning, mất lợi ích).
        ThreadPoolTaskExecutor containerExecutor = new ThreadPoolTaskExecutor();
        containerExecutor.setCorePoolSize(workers);
        containerExecutor.setMaxPoolSize(workers);
        containerExecutor.setQueueCapacity(100); // hàng đợi giới hạn kích thước tối đã 100
        // Queue đầy → caller tự chạy task thay vì reject (không mất message)
        containerExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        containerExecutor.initialize();

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(properties.pollTimeoutMs()))
                .batchSize(1)                    // COUNT=1 cho XREADGROUP → mỗi consumer nhận 1 message/lần
                .executor(containerExecutor)      // 8 threads cho 8 consumers → song song thật
                .errorHandler(t -> log.error("Stream poll error", t))
                .build();

        var container = StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // Đăng ký nhiều consumer với tên khác nhau trong cùng group.
        // Mỗi consumer có PEL riêng, Redis chia message cho các consumer.
        // Cách này tăng throughput hơn dùng executor vì container poll song song.
        for (int i = 0; i < workers; i++) {
            String workerName = CONSUMER_NAME + "-w" + i;
            container.receive(
                    Consumer.from(properties.consumerGroup(), workerName),
                    StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()),
                    consumer
            );
        }

        // KHÔNG start() ở đây — chờ onAppReady() tạo group xong mới start
        return container;
    }

    /**
     * Chạy SAU KHI app ready (ApplicationReadyEvent).
     *
     * Thứ tự thực thi:
     * 1. Đảm bảo stream key tồn tại (XINFO STREAM, nếu chưa có thì XADD dummy)
     * 2. Tạo consumer group (nếu đã có thì BUSYGROUP → bỏ qua)
     * 2b. Xóa dummy entry nếu vừa tạo (consumer đọc phải thì crash)
     * 3. Start container (bắt đầu poll XREADGROUP)
     *
     * Nếu stream bị xóa (Redis restart, eviction, user xóa tay) giữa
     * lần trước và bây giờ, XREADGROUP sẽ fail NOGROUP.
     * Vì vậy phải kiểm tra stream key tồn tại TRƯỚC khi tạo group.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        String streamKey = properties.streamKey();
        String consumerGroup = properties.consumerGroup();

        // 1. Đảm bảo stream key tồn tại
        //    Nếu stream chưa có → tạo bằng XADD dummy entry, lưu ID để xóa sau
        //    Nếu stream đã có → bỏ qua (XINFO thành công)
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
            // BUSYGROUP bị wrap trong RedisSystemException → check root cause
            if (ex.getCause() instanceof RedisBusyException) {
                log.info("Consumer group '{}' already exists", consumerGroup);
            } else {
                throw ex;
            }
        }

        // 2b. Xóa dummy entry nếu vừa tạo (consumer đọc phải thì crash)
        if (dummyId != null) {
            redisTemplate.opsForStream().delete(streamKey, dummyId);
        }

        // 3. Start container SAU KHI group đã tồn tại
        StreamMessageListenerContainer<?, ?> container =
                applicationContext.getBean(StreamMessageListenerContainer.class);
        container.start();
        log.info("Outbox stream container started");
    }
}
