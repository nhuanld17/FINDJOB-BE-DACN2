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
import java.util.concurrent.ThreadPoolExecutor;

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
    private final ApplicationContext applicationContext;
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
     * Tạo container lắng nghe stream với chế độ manual ACK.
     * Sử dụng nhiều consumer trong cùng group để xử lí đồng thời,
     * mỗi consumer có tên riêng, Redis phân phối message round-robin
     *
     * @Bean(destroyMethod = "stop") đảm bảo container stop khi app shutdown
     */
    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
            RedisConnectionFactory redisConnectionFactory,
            EventStreamConsumer consumer,
            OutboxStreamProperties outboxStreamProperties) {
        // Số lượng worker consumer chạy đồng thời (hardcode 8, không tự suy từ số core)
        int workers = 8;

        ThreadPoolTaskExecutor containerExecutor = new ThreadPoolTaskExecutor();
        containerExecutor.setCorePoolSize(workers);
        containerExecutor.setMaxPoolSize(workers);
        // 8 consumer = 8 task poll, mỗi task sống mãi trên 1 thread -> không có task
        // nào chờ trong queue. Để 0 cho đúng thực tế (queueCapacity=0 → SynchronousQueue);
        // queue chỉ có ý nghĩa nếu sau này có task ngắn hạn được nộp vào pool này.
        containerExecutor.setQueueCapacity(0);

        // Phòng hờ: nếu sau này pool này nhận task ngắn hạn, queue đầy thì caller
        // tự chạy task thay vì reject (không mất task). Với 8 task poll hiện tại
        // thì handler này không bao giờ kích hoạt.
        containerExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        containerExecutor.initialize();

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(outboxStreamProperties.pollTimeoutMs()))
                .batchSize(1) // COUNT = 1 cho XREADGROUP -> mỗi consumer nhận 1 message/ 1 lần
                .executor(containerExecutor)   // phân phối 8 thread cho 8 consumer chạy đồng thời
                .errorHandler(t -> log.error("Stream poll error", t))
                .build();

        // Tạo container
        var container = StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // Đăng kí nhiều consumer với tên khác nhau trong cùng 1 group.
        // Lưu ý: PEL là của GROUP — 1 PEL duy nhất cho cả group, không phải
        // mỗi consumer 1 cái. Mỗi entry trong PEL chỉ ghi tên consumer đang
        // giữ message. Tên consumer khác nhau để Redis chia message
        // round-robin giữa các consumer trong group.
        for (int i = 0; i < workers; i++) {
            String workerName = CONSUMER_NAME + "-w" + i;
            // Mỗi lần receive() đăng kí 1 consumer trong group → tạo 1 task poll,
            // chiếm 1 thread cố định (8 consumer = 8 thread). Tên consumer khác nhau
            // để Redis chia message round-robin giữa các consumer trong group.
            // ReadOffset.lastConsumed(): đọc tiếp từ message chưa xử lí gần nhất của
            // consumer này — không đọc lại message cũ đã XACK.
            container.receive(
                    Consumer.from(outboxStreamProperties.consumerGroup(), workerName),
                    StreamOffset.create(outboxStreamProperties.streamKey(), ReadOffset.lastConsumed()),
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
        String streamKey = outboxStreamProperties.streamKey();
        String consumerGroup = outboxStreamProperties.consumerGroup();

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
