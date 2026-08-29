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
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;


import java.net.InetAddress;
import java.time.Duration;

/**
 * Cấu hình phía CONSUME: tạo consumer group và container lắng nghe stream.
 *
 * Khởi tạo consumer group và StreamMessageListenerContainer
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
     * Được tạo từ hostname + timestamp base36, đảm bảo duy nhất giữa các instance
     * Dùng để đăng kí consumer trong group và để PendingReclaimer claim lại message
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
     *
     * - @Bean(destroyMethod = "stop"): Khi app shutdown, Spring gọi stop()
     * để ngừng poll message từ redis, tránh leak thread/connection
     *
     * receive(consumer, offset, listener): consumer tự gọi XACK sau khi xử lí xong
     * Không dùng receiAutoAck() vì auto-ack ngay khi nhận message, nếu ứng dụng
     * crash trước khi xử lỹ xong thì message bị mất vĩnh viễn
     *
     * ReadOffset.lastConsumed(): chỉ nhận message mới (XREADGROUP ">").
     * Message cũ chưa ACK do consumer crash sẽ do PendingReclaimer xử lí.
     *
     * pollTimeOut: thời gian chờ message mới trước khi kết thúc lệnh XREADGROUP.
     * Serializer mặc định là String, phù hợp với StringRedisTemplate
     *
     * StreamMessageListenerContainer<
     *      String,                          --> key của Stream
     *      MapRecord<String, String, String>--> Record đọc được
     */
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
        // Số lượng consumer song song - nên bằng số core hoặc 8 để tối ưu
        int workers = 8;

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofMillis(properties.pollTimeoutMs()))
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
     * Tại sao dùng @EventListener thay vì CommandLineRunner?
     * Container bean được tạo sớm (@Bean), nếu container.start() chạy trong @Bean
     * thì XREADGROUP sẽ gọi TRƯỚC khi consumer group tồn tại → lỗi NOGROUP.
     * @EventListener chạy sau khi tất cả bean đã sẵn sàng → đảm bảo group đã có.
     *
     * ReadOffset.from("0"): group mới đọc từ đầu Stream, không bỏ sót
     * message cũ. Nếu group đã tồn tại, redis trả về BUSYGROUP -> bỏ qua lỗi này
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        // Tạo consumer group (nếu đã có thì BUSYGROUP -> bỏ qua)
        try {
            redisTemplate.opsForStream().createGroup(
                    properties.streamKey(), ReadOffset.from("0"), properties.consumerGroup());
            log.info("Consumer group '{}' created on stream '{}'",
                    properties.consumerGroup(), properties.streamKey());
        } catch (RedisSystemException ex) {
            // BUSYGROUP bị wrap trong RedisSystemException -> check root cause
            if (ex.getCause() instanceof RedisBusyException) {
                log.info("Consumer group '{}' already exists", properties.consumerGroup());
            } else {
                throw ex;
            }
        }

        // Start container SAU KHI group đã tồn tại
        StreamMessageListenerContainer<?, ?> container =
                applicationContext.getBean(StreamMessageListenerContainer.class);
        container.start();
        log.info("Outbox stream container started");
    }
}

