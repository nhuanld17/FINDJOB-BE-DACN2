# Tối ưu hiệu suất gửi mail — kế hoạch chi tiết

**Dự án:** FINDJOB-BE | **Ngày:** 2026-09-04 | **Cập nhật:** 2026-09-07 — Brevo canary ĐẠT: 21 mail / 2.03s đo thật, RTT warm 287–499ms (xem §3.4). Trước đó 2026-09-06: BỎ SMTP, chỉ dùng Brevo HTTP API (gói Free), xem §3
**Cơ sở (đo thật 2026-09-04, 8 worker, smtp.gmail.com):** 21 mail ≈ 14s (~1,5 mail/s). Insert 21 row ~46ms, push 21 XADD ~50ms, claim/markSent vài ms — **~99% thời gian là chờ SMTP Gmail (1,5–5s/mail)**. Bottleneck là transport, không phải code.

**Nguyên tắc:** mọi việc ở §1 làm được ngay (không phụ thuộc quyết định transport). §3 là quyết định duy nhất nâng giới hạn throughput. §4 làm sau khi chốt §3.

### Đọc nhanh (1 phút) — đọc cái này trước, rồi mới đọc các §

- **Bottleneck cũ là SMTP Gmail (đã quyết định bỏ)** — mỗi mail chờ 1,5–5s. DB/Redis chỉ vài ms/mail → tối ưu code không tăng được mail/s, chỉ giảm số lệnh DB/Redis để chuẩn bị cho lưu lượng lớn hơn.
- **[CẬP NHẬT 2026-09-06] BỎ hẳn SMTP, chỉ giữ 1 đường gửi = Brevo HTTP API (gói Free, 300 mail/ngày)** — RTT 100–300ms/mail. Không giữ 2 đường (conditional provider) cho phức tạp: xóa code SMTP + config `spring.mail` + biến env Gmail. Chi tiết: §3.
- §1.4 đã implement (pollLoop + claimProcessingBatch + OutboxWorkerManager); **§3.2 HOÀN THÀNH + canary ĐẠT (2026-09-07):** EmailService đã chạy qua `MailSender` → Brevo, compile pass, đo thật **21 mail / 2.03s**, RTT warm **287–499ms** — số liệu đầy đủ ở §3.4. Cập nhật thêm 2026-09-07: cache `RestClient` trong HttpMailSender (bỏ TLS handshake lặp mỗi mail) + log timing `[BREVO] OK ... total=...ms`. KHÔNG đụng config `spring.mail`/biến `MAIL_*` (giữ nguyên, xem Điều kiện hoàn thành); §4 (nới concurrency) = pending, chỉ cần khi burst ≥100 mail.
- **Thứ tự bắt buộc:** §1 giảm số lệnh DB/Redis (xong) → §3 chuyển Brevo + bỏ SMTP → §4 nới concurrency.

### Thuật ngữ dùng trong plan

| Thuật ngữ | Nghĩa |
|---|---|
| claim | 1 câu UPDATE DB đổi status row `PENDING/QUEUED → PROCESSING`. Có điều kiện status trong WHERE nên khi 2 luồng cùng UPDATE 1 row, chỉ 1 luồng đổi được |
| XACK | lệnh Redis báo "đã xử lí xong", xóa message khỏi PEL |
| PEL | danh sách message Redis đã giao cho consumer nhưng chưa được XACK |
| deliveryCount | số lần message bị giao lại mà chưa XACK (đọc từ XPENDING) |
| DLQ | stream chứa message đã thử đủ số lần cho phép mà vẫn fail, để debug/xử lí thủ công |
| reclaimer | tiến trình chạy định kỳ, XCLAIM các message không được xử lí trong quá 60s (reclaimIdleMs) rồi gửi lại hoặc đẩy DLQ |

---

## 1. Việc làm được ngay — code thuần

> Mục đích của nhóm này không phải "tăng mail/s" (SMTP đang là bottleneck) mà là **giảm số lệnh DB/Redis cố định trên hot path**: hiện mỗi mail tốn 1 lệnh XPENDING + 1 DB SELECT + 2 DB UPDATE + 1 XACK. Sau khi chuyển Brevo và tăng số mail/giây, DB/Redis sẽ phải xử lí nhiều hơn — cần giảm số lệnh này trước.
>
> Check `deliveryCount` (XPENDING) giữ nguyên — chỉ bỏ được 1 DB SELECT/mail (§1.1).

### 1.1 Nhúng `maxRetries` vào message lúc push

**Cơ chế hiện tại:** `EventStreamProducer.push(Outbox outbox)` đã có sẵn entity trong tham số nhưng chỉ XADD `outboxId, eventType, aggregateType, aggregateId, payload`. Cả consumer (`onMessage`) lẫn reclaimer (`reclaim`) sau đó phải `findById(outboxId)` — 1 DB SELECT/message — chỉ để đọc lại `maxRetries` mà lúc push đã có sẵn.

**Triển khai — 4 việc, đánh số theo thứ tự làm:**

1. **`EventStreamProducer.push()`** — thêm 1 field `maxRetries` vào message:

   ```java
   fields.put("outboxId", outbox.getId().toString());
   fields.put("eventType", outbox.getEventType());
   fields.put("maxRetries", String.valueOf(outbox.getMaxRetries()));
   // ... aggregateType, aggregateId, payload như cũ
   ```

2. **`EventStreamConsumer.onMessage()`** — bỏ `findById`, đọc `maxRetries` từ message:

   ```java
   int maxRetries = Integer.parseInt(
           mapRecord.getValue().getOrDefault("maxRetries", "5"));
   ```

   Check `deliveryCount` giữ nguyên — chỉ thay nguồn `maxRetries` từ `findById` sang field này. `Outbox` + `OutboxRepository` không còn được dùng trong class này → xóa luôn import + field + tham số constructor.

3. **`PendingReclaimer.reclaim()`** — làm y hệt consumer: bỏ `findById`, đọc `maxRetries` từ message:

   ```java
   // THAY cho: int maxRetries = outboxRepository.findById(outboxId)
   //                .map(Outbox::getMaxRetries).orElse(5);
   int maxRetries = Integer.parseInt(
           mapRecord.getValue().getOrDefault("maxRetries", "5"));
   ```

   Xóa luôn dependency `OutboxRepository` + `Outbox` (import, field, tham số constructor) — reclaimer không còn đọc DB ở chỗ này.

4. **Tương thích message cũ** — entry đã nằm trong stream từ trước (chưa có field `maxRetries`) → `getOrDefault(..., "5")` trả về giá trị mặc định 5, đúng bằng `maxRetries` trong DB. Không cần migrate dữ liệu cũ.

**Kiểm tra:** bật `show-sql` (đang bật sẵn), chạy batch test → (a) sau mỗi `UPDATE ... status='PROCESSING'` **không còn** `select ... from outbox where id=?`; (b) gây 1 mail fail rồi đợi reclaimer reclaim (idle ≥ 60s) → **nhánh reclaim cũng không còn** `select ... from outbox where id=?`; (c) DLQ vẫn chạy khi message cũ (không field) fail đủ 5 lần.

### 1.2 Workers cấu hình được + HikariCP theo công thức

**Cơ chế hiện tại:**

- `OutboxStreamConfig.container()` hardcode `int workers = 8`. Giá trị này xác định `corePoolSize`/`maxPoolSize` của `ThreadPoolTaskExecutor` (với `queueCapacity=0`) và số lần lặp `container.receive()` đăng ký consumer trong group (`-w0..-w7`) → độ song song tối đa của pipeline = 8 message đồng thời. Thay đổi độ song song đòi hỏi sửa code và rebuild.
- `spring.datasource.hikari.maximum-pool-size: 10` (hardcode trong `application.yml`). Connection Postgres dùng chung cho toàn ứng dụng: outbox consumer, polling scheduler, reclaimer và các request API.

**Tương quan giữa workers và pool:** mỗi mail thực hiện 2–3 thao tác DB ngắn (vài ms): `claimProcessing` (UPDATE PROCESSING), `markSent` (UPDATE SENT), hoặc `revertToPendingWithError` (revert + ghi lỗi trong 1 TX) khi fail. Các thao tác này nằm ngoài khoảng block chờ SMTP — connection được acquire cho lệnh UPDATE rồi release trước khi gọi `mailSender.send()` (0,1–0,3s qua Brevo HTTP). Với 8 worker, số connection bị giữ đồng thời trong điều kiện bình thường thấp hơn 10 nên không xảy ra tranh chấp pool.

Khi tăng workers lên N: các worker hoàn thành phase SMTP trong khoảng thời gian gần nhau, hoặc nhiều worker cùng fail gần nhau → N worker acquire connection đồng thời. Pool 10 không đáp ứng đủ → các acquire vượt quá `connection-timeout` (30s) → ném `hikaricp.connections.timeout` → mail fail. Ngoài ra, pool dùng chung với polling scheduler (10s/lần), reclaimer (30s/lần) và request API nên cần thêm phần dự phòng.

**Công thức sizing `pool ≥ workers × 2 + 5`:** hệ số 2 dự phòng cho 2 phase DB liên tiếp của mỗi mail (phase claim của worker A có thể trùng phase markSent/revert của worker B); +5 dự phòng cho các thành phần dùng chung pool. workers 8 → tối thiểu 21; workers 16 → tối thiểu 37.

**Triển khai — 5 việc:**

1. **`OutboxStreamProperties`** — thêm field `int workers` vào record (prefix `app.outbox`):

   ```java
   // ...các field hiện có (streamKey, dlqStreamKey, consumerGroup, ...)
   int workers
   ```

2. **`application.yml`** — thêm dòng dưới `app.outbox` (giá trị mặc định 8 do placeholder cung cấp, record không có default):

   ```yaml
   app:
     outbox:
       workers: ${OUTBOX_WORKERS:8}
   ```

3. **`OutboxStreamConfig.container()`** — thay hằng số bằng property (method đã nhận `outboxStreamProperties` làm tham số):

   ```java
   // int workers = 8;  →  thay bằng:
   int workers = outboxStreamProperties.workers();
   ```

   `corePoolSize`/`maxPoolSize`/`queueCapacity=0` và vòng lặp `registerReceive` (N consumer `-w0..-w(N-1)`) tự theo biến này.

4. **`application.yml` (HikariCP)** — `maximum-pool-size` không hỗ trợ biểu thức trong YAML → đưa ra environment placeholder, điều chỉnh thủ công theo workers:

   ```yaml
   spring:
     datasource:
       hikari:
         maximum-pool-size: ${DB_POOL_SIZE:21}   # workers 8 → 21; workers 16 → 37
   ```

5. **Giám sát `hikaricp.connections.timeout`** — dùng metric từ §2; timeout xuất hiện nghĩa là pool không đủ, loại trừ nguyên nhân chờ HTTP.

**Kiểm tra:** set `OUTBOX_WORKERS=16` + `DB_POOL_SIZE=37` → ứng dụng khởi động thành công, log hiển thị 16 consumer `-w0..-w15`; batch 100 mail không ghi nhận connection timeout. Đối chứng: giữ pool 10, tăng workers lên 16 → xuất hiện `hikaricp.connections.timeout`.

### 1.3 Thymeleaf cache — xác nhận là xong

**Cơ chế:** mỗi mail, `EmailService` gọi `templateEngine.process("email/otp", context)` — parse template HTML từ classpath mỗi lần nếu cache tắt. Spring Boot **mặc định `spring.thymeleaf.cache=true`**; `application.yml` (file duy nhất, không có profile khác) không override → **cache đã bật sẵn, không có việc gì để làm**.

**Triển khai:** chỉ kiểm tra lại không ai thêm `spring.thymeleaf.cache: false` khi merge. Không tính mục này vào kỳ vọng hiệu năng (render 1–5ms vs chờ SMTP 1,5–5s).

### 1.4 Gộp claim + XACK — CHỈ khi chuyển `batchSize > 1`

**Cơ chế — mỗi mail tốn thêm 2 lệnh DB/Redis ngoài việc gửi mail:**
- **claim** — 1 câu UPDATE đổi status row từ `PENDING/QUEUED` sang `PROCESSING`; có điều kiện status trong WHERE nên khi 2 luồng cùng UPDATE 1 row, chỉ 1 luồng đổi được;
- **XACK** — 1 lệnh Redis báo "đã xử lí xong", xóa message khỏi PEL.

Hiện `batchSize=1` → mỗi lần XREADGROUP nhận đúng 1 message → mỗi mail phải chạy riêng 1 UPDATE + 1 XACK. Chi phí thực nhỏ (vài ms) nhưng tăng theo số mail.

**Dễ hiểu sai:** tăng `batchSize` lên N (COUNT=N) **không tự gộp được** claim/XACK. `batchSize` chỉ cho container nhận N message trong 1 lần XREADGROUP; container **vẫn dispatch từng message một cho `onMessage()`, xử lí tuần tự trên cùng 1 thread** → N message vẫn tốn N lần claim + N lần XACK. Tiết kiệm duy nhất là số lần XREADGROUP — vốn không phải bottleneck.

**Spring Data Redis KHÔNG có listener dạng batch** (kiểm tra trực tiếp trong jar 3.5.13 — version project đang dùng, xem [VERIFICATION]): interface duy nhất là `StreamListener<K,V>` với `onMessage(V)` nhận 1 record, và `StreamMessageListenerContainer` chỉ có API `receive(..., StreamListener)` — không có cách nào nhận `List<MapRecord>` từ container. **Để gộp claim + XACK** phải **bỏ container, tự poll**: mỗi vòng gọi 1 lệnh `opsForStream().read(Consumer, StreamReadOptions.count(N).block(...), StreamOffset)` — 1 lệnh XREADGROUP COUNT=N trả về `List<MapRecord>`, tự xử lí list đó:
1. **Claim 1 lần cả lô** — `claimProcessingBatch(List<Long> ids)`: 1 câu `UPDATE ... SET status='PROCESSING' WHERE id IN (:ids) AND status IN ('PENDING','QUEUED')` trả về **danh sách id được UPDATE thành công** (không phải số lượng — lí do và code: bước 3 bên dưới).
2. **Xử lí từng mail như cũ** — gọi handler từng message; fail thì revert riêng từng row (giữ nguyên logic hiện tại).
3. **XACK 1 lần cả lô** — `opsForStream().acknowledge(streamKey, group, ids...)` (varargs).

**Triển khai — 5 việc, đánh số theo thứ tự làm (khi cần, không làm bây giờ):**

1. **`OutboxStreamProperties` + `application.yml` — thêm property `containerBatchSize`.** KHÔNG tái dùng `batchSize` đang có: field đó là batch của polling scheduler (`OutboxPollingScheduler.lockPendingBatch()` đọc nó để SELECT row PENDING mỗi vòng), không liên quan XREADGROUP:

   ```java
   // OutboxStreamProperties — thêm field cuối record (@ConfigurationProperties bind theo tên)
   int containerBatchSize
   ```

   ```yaml
   app:
     outbox:
       ...
       # COUNT XREADGROUP của container — số message nhận mỗi lần poll.
       # >1 chỉ có tác dụng với đường poll theo lô (§1.4); giữ 1 cho tới khi xong §3.
       container-batch-size: ${OUTBOX_CONTAINER_BATCH:1}
   ```

2. **`OutboxStreamConfig.container()`** — thay hằng số bằng property:

   ```java
   .batchSize(outboxStreamProperties.containerBatchSize()) // COUNT = N cho XREADGROUP
   ```

   Lưu ý: `batchSize` của container chỉ giảm số lần XREADGROUP; container vẫn dispatch từng message — muốn gộp claim/XACK phải bỏ container theo bước 4.

3. **`OutboxService.claimProcessingBatch(List<Long> ids)` — trả về `List<Long>` các id được UPDATE thành công, không phải số lượng.** Lí do: `UPDATE ... WHERE id IN (:ids)` chỉ trả về *số row* đổi status, không cho biết *những id nào* được đổi. Với id không được UPDATE (row đã `SENT`, hoặc đã ở `PROCESSING` vì worker/reclaimer khác đổi trước), message tương ứng vẫn phải được ACK và bỏ qua — giống hệt nhánh "claim fail → ACK" hiện tại. Không biết chính xác id nào không được UPDATE thì không biết message nào phải bỏ qua → nguy cơ **gửi trùng mail**. `@Modifying @Query` của JPA không lấy được `RETURNING` → dùng `NamedParameterJdbcTemplate` (bean có sẵn qua `spring-boot-starter-jdbc`, đi kèm starter-data-jpa):

   ```java
   // OutboxService — thêm field (lombok @RequiredArgsConstructor tự inject)
   // CHÚ Ý import: org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
   // KHÔNG phải JdbcTemplate (org.springframework.jdbc.core.JdbcTemplate):
   // JdbcTemplate không có overload queryForList(String, Map, Class) — gọi với
   // (String, Map, Long.class) sẽ match overload varargs (String, Object...)
   // → trả List<Map<String,Object>>, lỗi compile khi gán vào List<Long>.
   private final NamedParameterJdbcTemplate jdbcTemplate;

   @Transactional
   public List<Long> claimProcessingBatch(List<Long> ids) {
       if (ids.isEmpty()) return List.of();
       // Postgres: UPDATE ... RETURNING id trả CHÍNH XÁC các id được UPDATE thành công.
       return jdbcTemplate.queryForList(
               """
               UPDATE outbox SET status = 'PROCESSING'
               WHERE id IN (:ids) AND status IN ('PENDING', 'QUEUED')
               RETURNING id
               """,
               Map.of("ids", ids),
               Long.class);
   }
   ```

   (Không muốn JdbcTemplate trong service → tách 1 `@Repository` nhỏ chứa riêng method này. Cấm để trong `OutboxRepository` JPA vì `@Modifying` không chạy được `RETURNING`.)

4. **`EventStreamConsumer` — bỏ `implements StreamListener`, thêm vòng lặp poll theo lô.** Container chỉ dispatch được từng message (không có batch listener — xem phần "Dễ hiểu sai"), nên đường batch phải tự poll bằng `opsForStream().read(...)`. `OutboxStreamConfig` đổi theo mục **4b** bên dưới. Code consumer (import thêm `RecordId`, `StreamReadOptions`, `Consumer`, `StreamOffset`, `ReadOffset`, `java.time.Duration`, `java.util.{ArrayList, HashSet, List, Set}`; bỏ import `StreamListener`):

   ```java
   public class EventStreamConsumer {   // KHÔNG còn implements StreamListener
       // Giữ nguyên: @Slf4j @Component @RequiredArgsConstructor + 4 field
       // (outboxService, stringRedisTemplate, outboxStreamProperties, eventHandlerRegistry)
       // — constructor tự sinh, không đổi. Import RedisStreamCommands + StreamRecords
       // (dùng bởi sendToDlq) đã có sẵn trong file.

       private volatile boolean running = false;  // MẶC ĐỊNH TẮT — worker chỉ chạy sau khi
       // 4b.start() bật true (consumer group đã tồn tại → hết NOGROUP race lúc startup);
       // 4b.stop() tắt về false để pollLoop thoát vòng while

       // ===== Vòng lặp poll của 1 worker — chạy trên thread riêng, thay cho container.receive(...) cũ =====
       public void pollLoop(String workerName) {
           while (running) {
               // 1 lệnh XREADGROUP COUNT=N, BLOCK=pollTimeout — không có message thì trả về list rỗng/null
               // @SuppressWarnings("unchecked") cho warning dưới đây: read(...) nhận varargs StreamOffset<String>... —
               // javac bắt buộc dựng mảng generic (new StreamOffset<String>[] — Java cấm tạo generic array) nên báo
               // "unchecked generic array creation for varargs parameter". Type witness ở điểm 5 chỉ sửa được target-type
               // inference, KHÔNG loại được warning varargs này — phải suppress tại khai báo local (áp cả initializer).
               @SuppressWarnings("unchecked")
               List<MapRecord<String, String, String>> records =
                       stringRedisTemplate.<String, String>opsForStream().read(  // type witness — bắt buộc, xem điểm 5
                               Consumer.from(outboxStreamProperties.consumerGroup(), workerName),
                               StreamReadOptions.empty()
                                       .count(outboxStreamProperties.containerBatchSize())
                                       .block(Duration.ofMillis(outboxStreamProperties.pollTimeoutMs())),
                               StreamOffset.create(outboxStreamProperties.streamKey(),
                                       ReadOffset.lastConsumed()));
               if (records == null || records.isEmpty()) continue;

               // 2. Claim 1 lần cả lô — RETURNING trả đúng các id được UPDATE thành công
               Set<Long> claimed = new HashSet<>(outboxService.claimProcessingBatch(
                       records.stream()
                               .map(r -> Long.parseLong(r.getValue().get("outboxId")))
                               .toList()));

               List<RecordId> toAck = new ArrayList<>();
               for (MapRecord<String, String, String> record : records) {
                   long outboxId = Long.parseLong(record.getValue().get("outboxId"));
                   // Không được UPDATE (row đã SENT hoặc worker khác đã đổi trước) -> ACK bỏ qua, như nhánh cũ
                   if (!claimed.contains(outboxId)) {
                       toAck.add(record.getId());
                       continue;
                   }
                   processRecord(record, toAck);   // KHÔNG claim lại — cả lô đã claim ở bước 2
               }

               // 3. XACK 1 lần cả lô (gồm id được UPDATE + id không được UPDATE + id vào DLQ); message fail KHÔNG nằm trong toAck
               acknowledge(toAck);
           }
       }

       // ===== Đường 1 message — giữ nguyên cho PendingReclaimer (call site không đổi) =====
       public void onMessage(MapRecord<String, String, String> mapRecord) {
           long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));
           // Nhánh reclaimer vẫn claim từng message như cũ
           if (!outboxService.claimProcessing(outboxId)) {
               log.debug("[OUTBOX] Skip outbox={} (already processing or SENT) -> ACK", outboxId);
               acknowledge(List.of(mapRecord.getId()));
               return;
           }
           List<RecordId> toAck = new ArrayList<>();
           processRecord(mapRecord, toAck);
           acknowledge(toAck);
       }

       // ===== Thân cũ của onMessage(MapRecord), thay 3 chỗ ACK thành toAck.add =====
       private void processRecord(MapRecord<String, String, String> mapRecord, List<RecordId> toAck) {
           long outboxId = Long.parseLong(mapRecord.getValue().get("outboxId"));
           try {
               long deliveryCount = getDeliveryCount(mapRecord);   // XPENDING — giữ nguyên từng message
               int maxRetries = Integer.parseInt(
                       mapRecord.getValue().getOrDefault("maxRetries", "5"));
               if (deliveryCount >= maxRetries) {
                   log.warn("[OUTBOX] outbox={} deliveryCount({}) >= maxRetries({}) → DLQ",
                           outboxId, deliveryCount, maxRetries);
                   sendToDlq(mapRecord);
                   outboxService.markFailed(outboxId,
                           "Exceeded max deliveries (" + deliveryCount + ")");
                   toAck.add(mapRecord.getId());                  // DLQ -> XACK
                   return;
               }
               eventHandlerRegistry.getByEventType(mapRecord.getValue().get("eventType"))
                       .handle(mapRecord.getValue().get("payload"));
               outboxService.markSent(outboxId);
               toAck.add(mapRecord.getId());                      // SENT -> XACK
               log.info("[OUTBOX] SUCCESS outbox={} → SENT + ACK", outboxId);
           } catch (Exception e) {
               // Fail -> revert PROCESSING→PENDING + ghi lỗi trong 1 TX (1 lệnh UPDATE atomic),
               // KHÔNG XACK — message nằm lại PEL chờ reclaimer
               log.error("Handle fail out={} - revert to PENDING, chờ reclaimer", outboxId, e);
               outboxService.revertToPendingWithError(outboxId, e.getMessage());
           }
       }

       // XACK nhiều id 1 lần — thay cho acknowledge(MapRecord) cũ
       private void acknowledge(List<RecordId> ids) {
           if (ids.isEmpty()) return;
           stringRedisTemplate.opsForStream().acknowledge(
                   outboxStreamProperties.streamKey(),
                   outboxStreamProperties.consumerGroup(),
                   ids.toArray(RecordId[]::new));
       }

       /**
        * Lấy deliveryCount từ PEL — số lần Redis đã giao message này cho consumer
        * mà chưa XACK. Dùng XPENDING tra theo message ID.
        * GIỮ NGUYÊN từ file hiện tại — không đổi gì (xem điểm bắt buộc số 3:
        * XPENDING không có API batch cho nhiều ID cụ thể).
        */
       private long getDeliveryCount(MapRecord<String, String, String> mapRecord) {
           var pending = stringRedisTemplate.opsForStream().pending(
                   outboxStreamProperties.streamKey(),
                   outboxStreamProperties.consumerGroup(),
                   Range.just(mapRecord.getId().getValue()), // start = end = ID message đang check
                   1);                                       // count: chỉ 1 ID nên 1 là đủ

           // Nếu có pending thì lấy ra DeliveryCount của message pending đó
           if (pending != null && !pending.isEmpty()) {
               return pending.get(0).getTotalDeliveryCount();
           }
           return 0;
       }

       /**
        * Chuyển message sang DLQ (dead-letter stream) để debug/requeue thủ công.
        * GIỮ NGUYÊN từ file hiện tại — không đổi gì.
        * Giới hạn 10000 entry trong DLQ bằng MAXLEN ~.
        */
       private void sendToDlq(MapRecord<String, String, String> mapRecord) {
           stringRedisTemplate.opsForStream().add(
                   StreamRecords.string(mapRecord.getValue())
                           .withStreamKey(outboxStreamProperties.dlqStreamKey()),
                   RedisStreamCommands.XAddOptions.maxlen(10000)
                           .approximateTrimming(true));
       }
   }
   ```

   Năm điểm bắt buộc:
   - Nhánh batch **không claim lại** trong `processRecord` (đã claim cả lô ở bước 1); nhánh 1-message của reclaimer **vẫn claim từng message** như cũ.
   - Giữ overload `onMessage(MapRecord)` → `PendingReclaimer.reclaim()` gọi `eventStreamConsumer.onMessage(mapRecord)` **không đổi 1 dòng**. Nếu đổi tên method thì phải sửa luôn call site trong reclaimer.
   - `getDeliveryCount` (XPENDING) vẫn từng message — Spring Data Redis không có API XPENDING cho nhiều ID trong 1 lệnh; mục tiêu của 1.4 là gộp claim + XACK, không phải XPENDING. Code đầy đủ đã in trong block trên — copy nguyên, không viết lại.
   - `getDeliveryCount` + `sendToDlq` **giữ nguyên code từ file hiện tại** (không đổi chữ nào). Import `org.springframework.data.domain.Range` của `getDeliveryCount` đã có sẵn trong file — không cần thêm.
   - **`opsForStream()` là generic method** `<HK, HV> StreamOperations<K, HK, HV>` — gọi dây chuyền `stringRedisTemplate.opsForStream().read(...)` thì javac suy ra `HK/HV = Object, Object` (target type KHÔNG chảy qua method chain) → lỗi `Incompatible types: List<MapRecord<String,Object,Object>>` vs `List<MapRecord<String,String,String>>`. **Phải ghi type witness**: `stringRedisTemplate.<String, String>opsForStream()`. Runtime an toàn: `StringRedisTemplate` dùng `StringRedisSerializer` cho hash key/value (bytecode `DefaultStreamOperations.deserializeRecord` dùng keySerializer/hashKeySerializer/hashValueSerializer của template) → field/value deserialize về String thật, không phải byte[].
   - **Warning `unchecked generic array creation for varargs parameter` ở chính call `read(...)` này là vấn đề KHÁC, type witness không hết được** — `read(...)` nhận varargs `StreamOffset<K>...`; với `K = String`, javac phải dựng `new StreamOffset<String>[]` tại call site, mà Java cấm tạo generic array → javac báo warning loại `[unchecked]` (IntelliJ cũng cảnh báo mặc định). Cách duy nhất là suppress: `@SuppressWarnings("unchecked")` ngay trên khai báo local `records` (áp cả initializer) — đã thêm trong block code mục 1.4. KHÔNG dùng `@SuppressWarnings("varargs")`: chuỗi đó chỉ chặn warning phía *khai báo* method ("heap pollution"), không áp cho call site này.

**4b. `OutboxStreamConfig` — bỏ bean container, thay bằng `OutboxWorkerManager`.**

**Cơ chế:** container cũ thực ra đang lo 3 việc mà đường batch phải tự lo lại: (1) vòng poll chạy lặp trên thread riêng, (2) lifecycle start/stop khi app ready/shutdown, (3) chống race với lúc tạo consumer group. `OutboxWorkerManager` — static class nằm ngay trong `OutboxStreamConfig` (không cần file bean mới) — thay thế đúng 3 việc đó: thay vì container + `receive()` 8 lần, giờ là `ThreadPoolTaskExecutor` + 8 task `pollLoop`.

**File `OutboxStreamConfig.java` sau khi đổi (phần giữ nguyên đánh dấu rõ):**

```java
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

    public static final String CONSUMER_NAME = buildConsumerName();
    private final StringRedisTemplate redisTemplate;
    private final ApplicationContext applicationContext;   // vẫn dùng để lấy OutboxWorkerManager
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
     * Thay cho bean container cũ. KHÔNG nộp task ở đây — nộp trong start()
     * (chạy sau XGROUP CREATE) để tránh NOGROUP race lúc startup.
     * destroyMethod = "stop" → Spring gọi OutboxWorkerManager.stop() khi app shutdown.
     */
    @Bean(destroyMethod = "stop")
    OutboxWorkerManager outboxWorkerManager(EventStreamConsumer consumer,
                                            OutboxStreamProperties props) {
        return new OutboxWorkerManager(consumer, props);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        String streamKey = outboxStreamProperties.streamKey();
        String consumerGroup = outboxStreamProperties.consumerGroup();

        // 1. Đảm bảo stream key tồn tại — GIỮ NGUYÊN
        RecordId dummyId = null;
        try {
            redisTemplate.opsForStream().info(streamKey);
        } catch (RedisSystemException e) {
            dummyId = redisTemplate.opsForStream().add(
                    StreamRecords.string(Map.of("_", "_")).withStreamKey(streamKey));
            log.info("Stream '{}' created (was missing)", streamKey);
        }

        // 2. Tạo consumer group — GIỮ NGUYÊN (bắt BUSYGROUP)
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

        // 2b. Xóa dummy entry — GIỮ NGUYÊN
        if (dummyId != null) {
            redisTemplate.opsForStream().delete(streamKey, dummyId);
        }

        // 3. ĐỔI: trước đây gọi container.start() → giờ start worker SAU khi group tồn tại
        applicationContext.getBean(OutboxWorkerManager.class).start();
        log.info("Outbox workers started");
    }

    /**
     * Quản lý N worker poll: executor + start() nộp task + stop() tắt cờ & shutdown.
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
            executor.setQueueCapacity(0);               // như cũ: mỗi worker 1 task sống mãi
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            executor.setThreadNamePrefix("outbox-worker-");   // dễ đọc log hơn container cũ
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
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Outbox workers chưa dừng sau 30s, shutdownNow");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

**Thêm 2 method vào `EventStreamConsumer`** (sát field `running`):

```java
public boolean isRunning() {
    return running;
}

public void setRunning(boolean running) {
    this.running = running;
}
```

**Thay đổi so với file hiện tại (tóm tắt):**
- **Bỏ:** bean `StreamMessageListenerContainer container(...)` + vòng `container.receive(...)`; import `RedisConnectionFactory`, `StreamMessageListenerContainer`, `Consumer`, `StreamOffset`, `MapRecord`.
- **Giữ lại import `ReadOffset`** — `onAppReady` vẫn dùng `ReadOffset.from("0")` khi `createGroup`.
- **Các file khác KHÔNG đổi:** `PendingReclaimer` (gọi `onMessage` như cũ), `OutboxPollingScheduler`, `EventStreamProducer`, các handler, `OutboxRepository`.
- **Giữ nguyên:** `CONSUMER_NAME`/`buildConsumerName()`, `onAppReady` bước 1/2/2b (chỉ đổi dòng cuối), toàn bộ `OutboxStreamProperties`.
- **Thêm:** class `OutboxWorkerManager`, import `TimeUnit`, 2 method `isRunning()`/`setRunning()` trong consumer.
- **Lý do `running` mặc định false** (sửa so với bản nháp cũ `true`): task chỉ nộp trong `start()` — nếu nộp lúc bean tạo, worker sẽ XREADGROUP trước khi group tồn tại → `NOGROUP` ngay lúc startup. Logic "cờ + nộp task" phải đồng bộ: cờ tắt thì chưa có task; cờ bật thì task đã ở trong thread pool.

5. **Trình tự nâng N an toàn:** chạy `pollLoop` với `container-batch-size: 1` TRƯỚC (mỗi lô đúng 1 message, hành vi y hệt hiện tại) → xác nhận hành vi không đổi → mới nâng lên 4–8.

**Lưu ý `reclaimIdleMs`:** idle của từng message tính từ lúc XREADGROUP trả về, không phải lúc message đó được xử lí. Với Brevo (100–300ms/mail), lô 8–16 mail xử lí xong < 5s → không đạt ngưỡng 60s idle → reclaimer không XCLAIM thừa.

**Kiểm tra:**
- `show-sql`: lô N=4 → đúng **1** câu `UPDATE ... WHERE id IN (...)` thay vì 4; XACK kiểm chứng bằng `XPENDING` → PEL rỗng sau mỗi lô.
- Giả lập từng nhánh: (a) lô toàn row `PENDING` → claim 4/4, gửi đủ, PEL rỗng; (b) 1 row trong lô đã `markSent` tay trước khi chạy → message đó chỉ ACK, handler KHÔNG gọi (không gửi trùng); (c) 1 mail fail → revertToPendingWithError (revert + ghi lỗi commit chung 1 TX), message KHÔNG bị XACK, reclaimer retry như cũ; (d) deliveryCount ≥ maxRetries → DLQ + markFailed + XACK.
- **Config & lifecycle (mục 4b):** khởi động → log `Started N outbox worker(s)`; `XINFO CONSUMERS <stream> <group>` thấy đúng N consumer `-w0..-w(N-1)`; SIGTERM → worker thoát trong < `pollTimeoutMs` (2s) + thời gian xử lí lô, không lỗi; restart liên tục 3 lần → không bao giờ có lỗi `NOGROUP` từ worker (vì task chỉ nộp sau khi group đã tạo).
- Đối chiếu log `show-sql`: số câu claim/XACK giảm ~N lần so với cùng N message đi từng cái một.

**Điều kiện làm:** chỉ sau khi xong §3 (Brevo) — lúc đó SMTP không còn là bottleneck, claim/XACK mới có khả năng thành bottleneck mới — **và** số đo cho thấy claim/XACK chiếm phần đáng kể (hiện tại vài ms/mail so với 1,5–5s chờ SMTP cũ → chưa đáng).

---

## 3. Transport — Brevo HTTP API, BỎ SMTP (P0, quyết định duy nhất nâng giới hạn throughput)

**[CẬP NHẬT 2026-09-06] BỎ hẳn smtp.gmail.com, chỉ giữ 1 đường gửi = Brevo HTTP API (gói Free).** SMTP chậm gấp ~10–20 lần (1,5–5s vs 100–300ms/mail) → không giữ 2 đường gửi (conditional provider) cho phức tạp: xóa code SMTP, xóa config `spring.mail`, xóa biến env Gmail. Outbox / consumer / reclaimer / handler **không đổi dòng nào**. Đã triển khai XONG + canary đo thật 2026-09-07 (§3.4). Xem §3.3 cho cảnh báo quota.

### 3.1 Cơ chế & so sánh (cơ sở quyết định bỏ SMTP)

| Tiêu chí | A — smtp.gmail.com (**BỎ**) | B — HTTP API Brevo (**CHỌN DUY NHẤT**) |
|---|---|---|
| Giới hạn | ~500–2.000 mail/**ngày** (1 tài khoản) | Brevo free 300/ngày, Resend free ~100/ngày; **trả phí: không còn giới hạn** |
| RTT 1 mail | 1,5–5s | 100–300ms (ước tính ban đầu); **đo thật 2026-09-07: 287–499ms khi connection warm, ~0.9–1.2s cho lần gửi đầu tiên của phiên (TLS handshake)** — xem §3.4 |
| Gửi song song | 421 khi burst | rate limit rõ (Brevo: 429 + header `x-sib-ratelimit-*`), retry backoff |
| Vì sao | mỗi mail = bắt tay SMTP + chờ server xử lí | 1 HTTP request, không giữ kết nối |

**Ghi chú Brevo (đường gửi duy nhất):** free 300 mail/ngày, không hết hạn; trả phí từ ~$9–39/tháng tùy volume (giá tham khảo 2026) thì **không còn giới hạn số mail gửi hằng ngày**. Gửi bằng 1 HTTP request `POST /smtp/email` (header `api-key`), RTT ~100–300ms; vượt rate limit → trả 429 kèm header `x-sib-ratelimit-*` để backoff theo thời lượng server yêu cầu.

**Kết luận:** không có tối ưu code nào cứu được SMTP → bỏ SMTP, chỉ giữ 1 đường HTTP API.

### 3.2 Triển khai — 1 đường gửi duy nhất (không đụng outbox/consumer/handler)

**[HOÀN THÀNH — Trạng thái 2026-09-07: Việc 1/3/4/5/7 + block `mail.http` + canary đều XONG. EmailService đã gọi `MailSender` → Brevo trên hot path thật. HttpMailSender cập nhật 2026-09-07: (a) cache `RestClient` build 1 lần duy nhất — trước đây build mới mỗi email nên mỗi mail mang thêm TCP+TLS handshake ~500–700ms; (b) thêm log timing `[BREVO] OK to=... total=...ms` để đo RTT trực tiếp. Kết quả canary đo thật: §3.4.]**

**Bước 1 — 1 impl duy nhất, KHÔNG conditional provider.** Interface `MailSender` đã có sẵn ở `infrastructure/mail/`:
```java
public interface MailSender {
    void sendHtmlEmail(String to, String subject, String html);
}
```
- **KHÔNG tạo `SmtpMailSender`** — SMTP bị xóa hẳn, không giữ làm fallback.
- `HttpMailSender implements MailSender` — RestClient gọi Brevo (`POST https://api.brevo.com/v3/smtp/email`, header `api-key`), retry 429/5xx (vòng lặp backoff + đọc header `x-sib-ratelimit-reset`), timeout 5–10s. Chỉ 1 impl → `@Component` thường, không `@ConditionalOnProperty`.
- `EmailService` giữ toàn bộ phần render template + các method `sendOtpEmail(...)`, chỉ đổi chỗ gửi cuối: field `JavaMailSender` → field `MailSender`, và `sendHtmlEmail(...)` ủy quyền cho interface. `EmailHandler` **không đổi dòng nào**.

**Bước 2 — config** (`application.yml` + `.env`): thêm block `mail.http` (chi tiết ở Việc 6, §3.2.1) và **XÓA cả block `spring.mail`** — xóa xong Spring Boot auto-config không còn tạo bean `JavaMailSender` (vì thiếu `spring.mail.host`) → hết hẳn đường SMTP.

**Bước 3 — canary:** chạy batch test qua `OutboxTestController`, theo dõi RTT + `outbox.email.failed` rồi mới cho lưu lượng thật.

#### 3.2.1 Hướng dẫn triển khai chi tiết — làm theo thứ tự [CẬP NHẬT 2026-09-06]

> API contract đã verify với primary source (developers.brevo.com/reference/send-transac-email, 2026-09-06): `POST https://api.brevo.com/v3/smtp/email`, header bắt buộc `api-key` + `Content-Type: application/json`; body: `sender` {name, email}, `to` [{email}], `subject`, `htmlContent` — `sender` + `subject` là bắt buộc khi không dùng `templateId`.

**Việc 1 — Interface `MailSender`** — đã tồn tại ở `infrastructure/mail/MailSender.java` (không cần tạo mới):

```java
package com.example.boilerplate.infrastructure.mail;

/** Abstraction lớp gửi mail — cho phép đổi transport (SMTP/HTTP API) không đụng handler. */
public interface MailSender {
    void sendHtmlEmail(String to, String subject, String html);
}
```

Lưu ý đặt tên: interface này đè lên `org.springframework.mail.MailSender` trong import của `EmailService` — khi import trong các impl, dùng **fully qualified name** hoặc import có dấu sao rõ ràng để tránh nhầm 2 loại.

**Việc 2 — (ĐÃ BỎ)** — không tạo `SmtpMailSender`; SMTP bị xóa hẳn, không giữ fallback.

**Việc 3 — `MailHttpProperties` (property class)** — file mới `infrastructure/mail/MailHttpProperties.java`:

```java
package com.example.boilerplate.infrastructure.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "mail.http")
public class MailHttpProperties {
    private String apiUrl;        // https://api.brevo.com/v3/smtp/email
    private String apiKey;        // BREVO_API_KEY từ .env
    private String senderEmail;   // địa chỉ người gửi (đã verify trên Brevo)
    private String senderName;    // tên hiển thị
    private int maxRetries = 3;   // số lần retry 429/5xx
    private long retryBaseMs = 500; // backoff: 500ms → 1s → 2s (nếu không đọc được header)
}
```

Đăng ký: thêm `@EnableConfigurationProperties(MailHttpProperties.class)` vào 1 config class có sẵn (hoặc annotate `@ConfigurationPropertiesScan` trên application class nếu chưa có).

**Việc 4 — `HttpMailSender` (Brevo)** — file mới `infrastructure/mail/HttpMailSender.java`:

```java
package com.example.boilerplate.infrastructure.mail;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpMailSender implements MailSender {

    private final MailHttpProperties props;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(props.getApiUrl())
                .defaultHeader("api-key", props.getApiKey())   // header xác thực Brevo (KHÔNG phải Bearer)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("name", props.getSenderName(), "email", props.getSenderEmail()),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", htmlContent);

        int attempts = 0;
        long backoff = props.getRetryBaseMs();
        while (true) {
            attempts++;
            try {
                client().post().body(body).retrieve().toBodilessEntity();
                return;   // 2xx → xong
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempts >= props.getMaxRetries()) {
                    throw new RuntimeException("Brevo send failed (HTTP " + status + ") to: " + to, e);
                }
                long wait = e.getResponseHeaders().getFirst("x-sib-ratelimit-reset") != null
                        ? Long.parseLong(e.getResponseHeaders().getFirst("x-sib-ratelimit-reset")) * 1000L
                        : backoff;
                log.warn("Brevo HTTP {} — retry {}/{} sau {}ms, to={}", status, attempts,
                        props.getMaxRetries(), wait, to);
                sleep(wait);
                backoff *= 2;
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while retrying Brevo send", ie);
        }
    }
}
```

Điểm cần đúng:
- Header xác thực là `api-key` (không phải `Authorization: Bearer`).
- 4xx khác (400, 401, 402...) **không retry** — ném ngay để outbox revert → PENDING → reclaimer xử lý như mail fail bình thường.
- `x-sib-ratelimit-reset` (giây còn lại đến khi reset, theo docs rate-limit của Brevo) ưu tiên hơn backoff tự tính; nếu server không trả header thì dùng backoff 500ms→1s→2s.
- `RestClient` có sẵn trong Spring Boot 3.5 (spring-web), không thêm dependency nào.

**Việc 5 — Sửa `EmailService` (xóa sạch SMTP) — [CẬP NHẬT 2026-09-06: CHƯA LÀM, đây là việc code tiếp theo]:** đổi field `private final JavaMailSender mailSender` thành `private final MailSender mailSender` (interface mới), method `sendHtmlEmail` chỉ còn 1 dòng `mailSender.sendHtmlEmail(to, subject, htmlContent);` — toàn bộ code `MimeMessage`/`MimeMessageHelper` xóa. Toàn bộ method khác (`sendOtpEmail`, `sendWelcomeEmail`, `sendApplicationAcceptedEmail`, `sendApplicationRejectedEmail`) **không đổi**. Import `org.springframework.mail.javamail.*` + `jakarta.mail.*` bỏ đi; grep xác nhận không còn `JavaMailSender` nào trong project. `EmailHandler` + outbox + các caller khác **không đổi dòng nào**.

File `EmailService.java` SAU KHI SỬA — copy nguyên, thay thế toàn bộ file:

```java
package com.example.boilerplate.infrastructure.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
@RequiredArgsConstructor
public class EmailService {

    // Interface MailSender (cùng package) — impl duy nhất là HttpMailSender (Brevo HTTP API).
    // KHÔNG import org.springframework.mail.MailSender — dùng đúng class cùng package này.
    private final MailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    /** Ủy quyền thẳng cho transport — mọi render template nằm ở các method phía dưới. */
    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        mailSender.sendHtmlEmail(to, subject, htmlContent);
    }

    public void sendOtpEmail(String to, String username, String otp) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("otp", otp);
        context.setVariable("expireMinutes", 5);

        String content = templateEngine.process("email/otp", context);
        sendHtmlEmail(to, "Your OTP Code", content);
    }

    public void sendWelcomeEmail(String to, String username) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("email", to);

        String content = templateEngine.process("email/welcome", context);
        sendHtmlEmail(to, "Welcome to Boilerplate!", content);
    }

    /**
     * Gửi email thông báo hồ sơ ĐƯỢC DUYỆT (ACCEPTED) cho ứng viên.
     *
     * @param to          Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName    Tên ứng viên
     * @param jobTitle    Tên job đã ứng tuyển
     * @param companyName Tên công ty nhà tuyển dụng
     */
    public void sendApplicationAcceptedEmail(String to, String fullName,
                                             String jobTitle, String companyName) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("jobTitle", jobTitle);
        context.setVariable("companyName", companyName);

        String content = templateEngine.process("email/application-accepted", context);
        sendHtmlEmail(to, "Hồ sơ của bạn đã được duyệt - " + companyName, content);
    }

    /**
     * Gửi email thông báo hồ sơ BỊ TỪ CHỐI (REJECTED) cho ứng viên, kèm lý do từ chối.
     *
     * @param to             Email ứng viên (luôn có — không phụ thuộc isPublic)
     * @param fullName       Tên ứng viên
     * @param jobTitle       Tên job đã ứng tuyển
     * @param companyName    Tên công ty nhà tuyển dụng
     * @param rejectedReason Lý do từ chối (đã validate bắt buộc ở service)
     */
    public void sendApplicationRejectedEmail(String to, String fullName,
                                             String jobTitle, String companyName,
                                             String rejectedReason) {
        Context context = new Context();
        context.setVariable("fullName", fullName);
        context.setVariable("jobTitle", jobTitle);
        context.setVariable("companyName", companyName);
        context.setVariable("rejectedReason", rejectedReason);

        String content = templateEngine.process("email/application-rejected", context);
        sendHtmlEmail(to, "Kết quả ứng tuyển - " + companyName, content);
    }
}
```

Thay đổi so với file hiện tại: bỏ 5 import (`jakarta.mail.MessagingException`, `jakarta.mail.internet.MimeMessage`, `org.springframework.mail.javamail.JavaMailSender`, `org.springframework.mail.javamail.MimeMessageHelper`, `org.springframework.scheduling.annotation.Async` — import này vốn thừa từ trước, không có `@Async` nào trong class); field `JavaMailSender mailSender` → `MailSender mailSender`; thân `sendHtmlEmail` rút còn 1 dòng ủy quyền. **Lombok `@RequiredArgsConstructor` tự tiêm bean `HttpMailSender` (impl duy nhất của `MailSender`) — không cần `@Qualifier` vì chỉ có 1 bean implement interface này.**

**Việc 6 — Config `application.yml` + `.env` (bỏ SMTP):**

```yaml
# application.yml — block mail mới (thay cho block spring.mail bị xóa)
mail:
  http:
    api-url: ${BREVO_API_URL:https://api.brevo.com/v3/smtp/email}
    api-key: ${BREVO_API_KEY:}
    sender-email: ${MAIL_FROM_EMAIL:}
    sender-name: ${MAIL_FROM_NAME:FindJob}
```

**Việc 7 — Biên dịch & verify:** `mvnw.cmd -q compile` phải pass; app khởi động không lỗi (không còn bean/đường SMTP nào).

**Việc 8 — Canary (sau khi có API key):**
1. Trong dashboard Brevo: tạo account → verify domain/sender → SMTP & API → tạo API key.
2. Paste `BREVO_API_KEY` vào `.env`, restart.
3. Gửi 1 mail đơn qua endpoint test → vào inbox thật + dashboard Brevo xem transactional log.
4. Bắn batch 21 mail qua `POST /api/test/outbox/send` → log `[OUTBOX] SUCCESS` 21/21 trong vài giây; dashboard Brevo hiện 21 đã gửi, quota còn 279.
5. Theo dõi `outbox.email.failed` trong log — phải bằng 0.
6. Trường hợp quota 300/ngày cạn: Brevo trả 402 (payment required) — KHÔNG retry (code trên đã đúng), mail fail → reclaimer xử lý như mail fail bình thường.

**Điều kiện hoàn thành:** app chạy với đúng 1 đường gửi (Brevo) — code SMTP xóa hết (Việc 5); config `spring.mail` + biến `MAIL_*` GIỮ NGUYÊN (quyết định 2026-09-06 — bean chết, giữ cặp config+env, xóa một trong hai mà không xóa cả hai sẽ fail lúc start vì `${MAIL_HOST}` unresolved); canary 21 mail pass; dashboard Brevo + inbox xác nhận.

### 3.3 Cảnh báo quota Brevo Free

**Quyết định:** dùng Brevo **Free** (không mua gói) — đổi "RTT chậm 1,5–5s (SMTP)" lấy "RTT nhanh 100–300ms nhưng quota thấp hơn".

**Cảnh báo quota (đọc trước khi chuyển hẳn):** Brevo Free chỉ **300 mail/ngày** — **thấp hơn Gmail Workspace ~2.000/ngày đang dùng ~6,5 lần**. Phù hợp giai đoạn dev/thử nghiệm; khi volume thật vượt ~300/ngày phải nâng gói trả phí (~$9–39/tháng theo ghi chú §3.1 — giá chưa verify trực tiếp với Brevo) — khi đó bản thân code không cần đổi gì, chỉ nâng gói.

**Số liệu đã verify với primary source (developers.brevo.com/docs/api-limits, 2026-09-06):** `POST /v3/smtp/email` general tier (mọi gói kể cả Free) = **1.000 RPS** (3.600.000 RPH); vượt → 429 kèm rate-limit headers `x-sib-ratelimit-*` để backoff. Rate limit không phải vấn đề ở mọi quy mô dự kiến; giới hạn thật là quota 300/ngày của gói Free.

### 3.4 Kết quả canary đo thật — 2026-09-07 00:47

**Cấu hình lúc đo:** Brevo HTTP API · 8 workers · `OUTBOX_CONTAINER_BATCH=1` (đã sửa từ 50 — sự cố ghi ở §5) · HttpMailSender đã cache RestClient · log timing `[BREVO] OK ... total=...ms` bật.

| Chỉ số | Gmail SMTP (baseline 2026-09-04) | Brevo HTTP (đo 2026-09-07) |
|---|---|---|
| 21 mail, tổng | ~14s | **2.03s** (từ POST vào 00:47:06.553 đến mail cuối SENT 00:47:08.582) |
| Thông lượng | ~1,5 mail/s | **~10,3 mail/s** (tính trên khoảng gửi thật 1.86s) |
| RTT 1 mail — đợt 1 (8 mail song song, TLS mới) | 1,5–5s | 876–1163ms |
| RTT 1 mail — đợt 2–3 (connection tái sử dụng) | — | **287–499ms** |
| Phân bố worker | — | 8 worker chia đều 2–3 mail mỗi thằng, 3 đợt: 8 + 8 + 5 |

**Đọc số liệu:**
- RTT Brevo thật ≈ **300–500ms/mail** khi connection warm — con số 100–300ms ở §3 là ước tính dưới biên; chỉnh kỳ vọng về 300–500ms.
- Phần dôi ~500–700ms ở đợt 1 là TLS handshake lần đầu của phiên. Trước khi cache RestClient, **mỗi mail đều mang phần này** → ~1.05s/mail (đo 2026-09-06) → sau cache chỉ mail đầu tiên của connection mới tốn.
- Không có 429; thời điểm đo quota còn ~279/300.

**Quyết định:** giữ nguyên 8 workers + COUNT=1. 21 mail/2s dư dả so với quota 300/ngày; §4 chỉ mở lại khi burst ≥100 mail trở thành yêu cầu thực tế.

---

## 4. Nới concurrency — sau khi xong §3

### 4.1 Cơ chế

**Cơ chế:** mỗi stream worker chặn trong 1 lần gửi → **số worker = số request HTTP xử lí đồng thời**. Không cần executor trung gian: tăng `app.outbox.workers` (mục 1.2) là tăng concurrency gửi, kèm Hikari pool theo công thức.

**Lưu ý chống gửi trùng:** không tách "claim nhanh → gửi qua queue riêng" — worker sẽ nhả status PROCESSING nhưng mail chưa gửi; nếu mail nằm trong queue quá `reclaimIdleMs` (60s), reclaimer sẽ coi như không còn worker xử lí message này → XCLAIM gửi lại → gửi trùng. Giữ gửi đồng bộ trong `onMessage` cho tới khi số đo chứng minh cần tách.

**Triển khai:** bắt đầu 8–16 workers, theo dõi counter 429: tăng dần workers, counter 429 tăng → dừng ở mức đó (HttpMailSender backoff đã tự làm chậm).

### 4.2 Tiêu chí hoàn thành

Burst 100 mail ≤ vài giây, lỗi 429 không tăng vô hạn, không vượt quota ngày (300/ngày gói Free).

---

## 5. Đừng làm — và cơ chế vì sao vô ích

| Việc | Cơ chế vì sao vô ích |
|---|---|
| Tăng `batchSize` để gửi nhanh hơn | Batch chỉ gộp lệnh XREADGROUP; với container, message vẫn được dispatch **từng cái một, xử lí tuần tự, chặn tới khi gửi xong**; với đường tự poll (§1.4), các message trong 1 lô vẫn xử lí tuần tự trên 1 thread — không tăng số mail song song. **⚠ ĐÃ XẢY RA THẬT 2026-09-06:** đặt `OUTBOX_CONTAINER_BATCH=50` khiến 1 worker húp trọn 13/21 message còn 7 worker block 2s trong XREADGROUP → tổng 14.7s; sửa về 1 → 2.03s (chi tiết ở §3.4) |
| Tách stream OTP riêng | Chỉ giúp OTP không xếp sau mail thường; RTT từng mail đã nhanh nhờ Brevo — không còn lợi ích đáng kể |
| Scale ngang nhiều instance | Consumer group + `FOR UPDATE SKIP LOCKED` chạy được, nhưng **tổng quota vẫn của 1 tài khoản Brevo** — nhiều máy không gửi được nhiều hơn |
| Pipeline XADD cho polling scheduler | Polling ≤ 100 row/10s, không phải hot path; fast path listener đã XADD song song ~50ms/21 mail |

---

## 6. Lộ trình tổng

**[CẬP NHẬT TRẠNG THÁI 2026-09-06]**

| Bước | Làm | Xong khi | Trạng thái |
|---|---|---|---|
| 0 | Đo baseline | con số lặp lại được | ✅ xong (đo 2026-09-04: 21 mail ≈ 14s trên Gmail) |
| 1 | §1.1 + §1.2 (§1.3 chỉ kiểm tra cache) | hết `findById` trên hot path (XPENDING giữ nguyên); workers cấu hình được; DLQ/retry không đổi | ✅ xong (kèm §1.4 + §4b: pollLoop + claimProcessingBatch + OutboxWorkerManager đã implement) |
| 2 | §3 chuyển Brevo HTTP API + xóa code SMTP (HttpMailSender, EmailService; config giữ nguyên theo quyết định 2026-09-06) | app chỉ còn 1 đường gửi, code không còn SMTP | ✅ xong — canary 2026-09-07: 21 mail / 2.03s, RTT warm 287–499ms (§3.4) |
| 3 | §4 nới concurrency | burst 100 mail đạt chỉ tiêu; 429 không mất kiểm soát; không vượt quota | ⏳ pending — canary 21 mail đạt 2.03s với 8 workers, không 429; chỉ cần làm khi burst ≥100 mail thành yêu cầu thật |
| 4 | Tùy chọn: stream OTP, scale ngang | theo mục tiêu riêng từng mục | ⏳ pending |

Thứ tự bắt buộc: **0 → 1 → 2 → 3**.
