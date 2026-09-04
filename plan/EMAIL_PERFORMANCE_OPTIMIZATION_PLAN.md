# Tối ưu hiệu suất gửi mail — kế hoạch chi tiết

**Dự án:** FINDJOB-BE | **Ngày:** 2026-09-04
**Cơ sở (đo thật 2026-09-04, 8 worker, smtp.gmail.com):** 21 mail ≈ 14s (~1,5 mail/s). Insert 21 row ~46ms, push 21 XADD ~50ms, claim/markSent vài ms — **~99% thời gian là chờ SMTP Gmail (1,5–5s/mail)**. Bottleneck là transport, không phải code.

**Nguyên tắc:** mọi việc ở §1, §2 làm được ngay (không phụ thuộc quyết định transport). §3 là quyết định mở trần năng suất. §4 làm sau khi chốt §3. Mọi thay đổi đối chiếu baseline của §2.

---

## 1. Việc làm được ngay — code thuần

> Mục đích thật của nhóm này không phải "tăng mail/s" (SMTP đang nghẽn) mà là **dọn chi phí cố định trên hot path**: mỗi mail hiện tốn 1 lệnh XPENDING + 1 DB SELECT + 2 DB UPDATE + 1 XACK. Khi nào transport mở (nhánh B) thì DB/Redis không được phép thành nghẽn mới — nên phải dọn trước.
>
> **Quyết định (2026-09-04): GIỮ check XPENDING trong consumer** — xem §1.1. Thứ thật sự bỏ được trên hot path là 1 DB SELECT/mail (§1.2).

### 1.1 GIỮ check `deliveryCount` trong `EventStreamConsumer` (quyết định — không xóa)

**Cơ chế hiện tại** — trong `onMessage()`, sau khi `claimProcessing()` giành được quyền:
1. `getDeliveryCount(mapRecord)` gọi `XPENDING <stream> <group> <id> <id> COUNT 1` → 1 Redis round-trip chỉ để đọc `totalDeliveryCount` của đúng message đang xử lí.
2. `outboxRepository.findById(outboxId)` → 1 DB SELECT để lấy `maxRetries` (sẽ bỏ ở §1.2).
3. Nếu `deliveryCount >= maxRetries` → `sendToDlq()` + `markFailed()` + ACK (consumer tự quyết DLQ).

Song song, `PendingReclaimer.reclaim()` (30s/lần) là **kênh duy nhất đưa message trở lại vòng xử lí**:
- Quét PEL bằng `XPENDING ... Range.unbounded() COUNT 50`, lọc message idle ≥ 60s.
- `deliveryCount = pendingMessage.getTotalDeliveryCount()` — đọc từ chính kết quả XPENDING, không tốn RTT riêng.
- `deliveryCount < maxRetries` → `XCLAIM` rồi gọi thẳng `eventStreamConsumer.onMessage(...)`.
- `deliveryCount >= maxRetries` → DLQ + XACK + `markFailed()`.

**Vì sao GIỮ check (đính chính sau khi phân tích + kiểm chứng):**
- Message đến tay consumer chỉ qua 2 cửa: (a) lần giao đầu tiên — XREADGROUP của container đọc bằng offset `>` (đã kiểm chứng `ReadOffset.lastConsumed()` = `">"` trong spring-data-redis 3.5.13) nên **không bao giờ giao lại** entry đã nằm PEL; (b) reclaimer `XCLAIM` chuyển vào. Kịch bản "consumer và reclaimer cùng giành 1 message" **không xảy ra được**.
- Nhưng có một ca biên thật: reclaimer đọc `deliveryCount` **trước** khi `XCLAIM`, mà `XCLAIM` tự **tăng counter thêm 1** (Redis docs) → message được reclaimer cho phép ở mức `maxRetries − 1` đến tay `onMessage` với count đã = `maxRetries`. Check trong consumer kích hoạt **đúng ở ca này**: DLQ luôn, **không gửi** lần SMTP thừa cuối cùng. Bỏ check → mỗi mail doomed (fail mọi lần thử) tốn thêm **1 lần gửi thất bại** rồi mới DLQ ở chu kỳ sau.
- Cái giá của check: 1 Redis RTT/mail (~0,2ms local) ≈ **< 0,1%** so với chờ SMTP 1,5–5s → chấp nhận được; sang nhánh B (100–300ms/mail) vẫn < 1%.
- Bonus: check là lưới an toàn nếu sau này xuất hiện kênh redelivery thứ hai quên cổng chặn.

**Triển khai:** không có — giữ nguyên `getDeliveryCount(...)`, khối so sánh `deliveryCount >= maxRetries` và `sendToDlq(...)` trong `EventStreamConsumer`. Chỉ thay nguồn `maxRetries` từ `findById` sang field trong message (§1.2).

**Lỗ hổng đã biết, để ngỏ (không xử lí trong đợt này):** check dựa trên deliveryCount **của từng entry Redis**, mà polling scheduler re-push row `PENDING` mỗi 10s sẽ XADD **entry mới với deliveryCount reset về 1** → `maxRetries` không chặn cứng được *tổng* số lần gửi SMTP thật (chỉ khi reclaimer DLQ một entry sau đủ 5 lần giao thì row mới thành FAILED). Muốn chặn chính xác → đếm lượt thử ở DB trên đường fail (atomic revert + count, FAILED khi hết lượt) — đó là thay đổi đúng đắn về correctness nhưng đổi ngữ nghĩa `retry_count`, nên tách ra quyết định riêng, không trộn vào đợt tối ưu này.

**Kiểm tra:** hành vi không đổi — chạy batch test, XPENDING vẫn xuất hiện trong log; gây lỗi nhân tạo fail liên tục → đúng số lần thử như trước, lần chạm `deliveryCount >= maxRetries` bị chặn và vào DLQ không gửi thêm.

### 1.2 Nhúng `maxRetries` vào message lúc push

**Cơ chế hiện tại:** `EventStreamProducer.push(Outbox outbox)` đã cầm sẵn entity nhưng chỉ XADD `outboxId, eventType, aggregateType, aggregateId, payload`. Cả consumer (`onMessage`) lẫn reclaimer (`reclaim`) sau đó phải `findById(outboxId)` — 1 DB SELECT/message — chỉ để đọc lại `maxRetries` mà lúc push đã có trong tay.

**Triển khai:**

1. `EventStreamProducer.push()` — thêm 1 field:
```java
fields.put("outboxId", outbox.getId().toString());
fields.put("eventType", outbox.getEventType());
fields.put("maxRetries", String.valueOf(outbox.getMaxRetries()));
// ... aggregateType, aggregateId, payload như cũ
```
2. `EventStreamConsumer.onMessage()` — thay 1 lệnh đọc DB (`findById`):
```java
int maxRetries = Integer.parseInt(
        mapRecord.getValue().getOrDefault("maxRetries", "5"));
```
(check `deliveryCount` ở §1.1 vẫn GIỮ NGUYÊN — chỉ thay nguồn `maxRetries` từ `findById` sang field này).
3. `PendingReclaimer.reclaim()` — cùng cách thay `findById`, xóa dependency `OutboxRepository`/`Outbox`.
4. **Tương thích message cũ:** entry đã nằm trong stream từ trước (chưa có field `maxRetries`) → `getOrDefault(..., "5")` rơi về mặc định 5 — đúng bằng `maxRetries` trong DB. Không cần migrate dữ liệu cũ.

**Kiểm tra:** bật `show-sql` (đang bật sẵn), chạy batch test → sau mỗi `UPDATE ... status='PROCESSING'` **không còn** `select ... from outbox where id=?`; DLQ vẫn chạy khi message cũ (không field) fail đủ 5 lần.

### 1.3 Workers cấu hình được + HikariCP theo công thức

**Cơ chế hiện tại:** `OutboxStreamConfig.container()` **hardcode `int workers = 8`** — muốn tăng concurrency phải sửa code. DB pool `maximum-pool-size: 10` được chốt "cho ứng dụng vừa", không tính theo số worker. Mỗi mail hiện đụng DB 2–3 lần UPDATE/SELECT ngắn (vài ms, **ngoài** khoảng chờ SMTP) nên 8 worker không bóp nghẽn pool — nhưng tăng worker mà không tăng pool sẽ gặp `hikaricp.connections.timeout` đúng lúc đang cần gửi nhiều.

**Triển khai:**
1. `OutboxStreamProperties` — thêm field `int workers` (mặc định 8).
2. `application.yml` — `app.outbox.workers: ${OUTBOX_WORKERS:8}`.
3. `OutboxStreamConfig.container()` — đọc `outboxStreamProperties.workers()` cho `corePoolSize`/`maxPoolSize`/`queueCapacity=0` và vòng lặp `registerReceive` (8 → N worker `-w0..-wN`).
4. `application.yml` (HikariCP) — đặt theo công thức **pool ≥ workers × 2 + 5**: workers 8 → `maximum-pool-size: 21`; workers 16 → 37. Thêm 5 là headroom cho request API + polling scheduler + reclaimer.
5. (Giai đoạn 0) theo dõi `hikaricp.connections.timeout` — xuất hiện timeout là do pool thiếu, không phải Gmail chậm.

**Kiểm tra:** đổi `OUTBOX_WORKERS=16` + pool 37 → vẫn start được, 16 thread `-w0..-w15` trong log; batch 100 mail không có connection timeout.

### 1.4 Thymeleaf cache — xác nhận là xong

**Cơ chế:** mỗi mail, `EmailService` gọi `templateEngine.process("email/otp", context)` — parse template HTML từ classpath mỗi lần nếu cache tắt. Spring Boot **mặc định `spring.thymeleaf.cache=true`**; `application.yml` (file duy nhất, không có profile khác) không override → **cache đã bật sẵn, không có việc gì để làm**.

**Triển khai:** chỉ kiểm tra lại không ai thêm `spring.thymeleaf.cache: false` khi merge. Không tính mục này vào kỳ vọng hiệu năng (render 1–5ms vs chờ SMTP 1,5–5s).

### 1.5 Gộp claim + XACK — CHỈ khi chuyển `batchSize > 1`

**Cơ chế:** hiện `batchSize=1` → mỗi lần XREADGROUP nhận 1 message → 1 `claimProcessing` UPDATE + 1 XACK. Nếu sau này tăng COUNT lên N thì container **vẫn gọi `onMessage` từng message một, tuần tự trên cùng 1 thread** — nên claim/XACK vẫn rời rạc N lần. Muốn gộp thật phải chuyển sang **batch listener**.

**Triển khai (khi cần, không làm bây giờ):**
- `EventStreamConsumer` đổi từ `implements StreamListener<...>` sang `BatchMessageListener<...>` nhận `List<MapRecord>`.
- Thêm repository method `claimProcessingBatch(List<Long> ids)`: `UPDATE ... SET status='PROCESSING' WHERE id IN (:ids) AND status IN ('PENDING','QUEUED')` — trả về số row giành được.
- Xử lí từng mail trong batch như cũ (fail → revert riêng từng row); cuối cùng XACK 1 lần cả danh sách: `opsForStream().acknowledge(streamKey, group, ids...)` (varargs).
- Điều kiện làm: chỉ sau nhánh B + số đo cho thấy claim/XACK chiếm phần đáng kể (hiện tại vài ms/mail — chưa đáng).

---

## 2. Đo baseline — Giai đoạn 0 (bắt buộc làm đầu tiên)

### 2.1 Cơ chế

`spring-boot-starter-actuator` đã có trong `pom.xml` → `MeterRegistry` (Micrometer) đã tự cấu hình, chỉ cần inject và dùng. Ba loại dụng cụ:

- **Timer** — đo thời gian 1 chặng, tự tính p50/p95/p99 (đặt `.publishPercentiles(0.5, 0.95, 0.99)`).
- **Counter** — đếm số lần biến cố (SENT, FAILED, 421, DLQ...).
- **Gauge + AtomicLong** — giá trị "mực nước" (backlog DB, độ dài PEL), cập nhật định kỳ vì không phải biến cố.

### 2.2 Bảng gắn dụng cụ (file → metric → điểm chèn)

| File | Metric (timer/counter/gauge) | Điểm chèn |
|---|---|---|
| `EventStreamProducer.push()` | timer `outbox.push` | bọc toàn bộ body — **1 chỗ duy nhất** đo cả fast path (listener) lẫn polling scheduler |
| `EventStreamConsumer.onMessage()` | timer `outbox.claim` quanh `claimProcessing`; timer `outbox.commit` quanh `markSent` + `acknowledge`; counter `outbox.email.sent` ở log SUCCESS; counter `outbox.email.failed` ở catch | sau 1.2, `onMessage` = claim → check deliveryCount (XPENDING + so `maxRetries` từ message, giữ cố ý theo §1.1) → handler → markSent/XACK — điểm chèn timer nằm giữa các bước |
| `EmailService.sendHtmlEmail()` | timer `outbox.smtp.send` quanh `mailSender.send(mimeMessage)`; counter `outbox.smtp.error.421` khi exception message chứa `"421"` | **1 chỗ duy nhất** — mọi loại mail (OTP/welcome/...) đều chui qua method này |
| `PendingReclaimer.reclaim()` | counter `outbox.reclaimed` khi XCLAIM ok; counter `outbox.dlq` ở nhánh DLQ | DLQ có 2 nơi quyết: consumer (check §1.1 bắt `deliveryCount >= maxRetries`) và reclaimer — gắn counter `outbox.dlq` ở cả 2 |
| **`OutboxMetrics` (component mới)** | gauge `outbox.backlog` = count row `PENDING + QUEUED`; gauge `outbox.pel` = số message chưa ack | `@Scheduled` 30s/lần: query count (thêm `countByStatusIn` vào `OutboxRepository`), và `opsForStream().pending(streamKey, group).getCount()` (XPENDING dạng summary — rẻ, không liệt kê entry) |

Mẫu chèn timer:
```java
private final MeterRegistry meterRegistry;          // inject qua constructor

Timer.Sample sample = Timer.start(meterRegistry);
outboxService.claimProcessing(outboxId);            // chặng cần đo
sample.stop(Timer.builder("outbox.claim")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry));
```
Counter: `meterRegistry.counter("outbox.email.sent").increment();`

**Expose actuator** trong `application.yml` để xem qua HTTP:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

### 2.3 Benchmark script (tạo mới `plan/scripts/benchmark-mail.sh`)

**Cơ chế:** endpoint test hiện có `POST /api/test/outbox/send` chỉ bơm đúng 21 mail (danh sách `TEST_EMAILS` cố định). Muốn N = 100 phải cho bơm theo số lượng.

**Triển khai:**
1. `OutboxTestService` — thêm `send(int count)`: dùng 21 email có sẵn, thiếu thì sinh thêm `"loadtest" + i + "@emalupe.com"`.
2. `OutboxTestController` — `@PostMapping("/send")` nhận `@RequestParam(defaultValue = "21") int count`.
3. Script chạy:
   - `POST /api/test/outbox/send?count=100` → nhận danh sách outboxId.
   - Chờ counter `outbox.email.sent` đạt 100 (poll `/actuator/metrics/outbox.email.sent`), ghi thời gian drain.
   - Đọc `/actuator/metrics/outbox.commit` + `outbox.smtp.send` → p50/p95/p99.
   - In 1 dòng tổng kết: `N=100 | drain=Xs | p50/p95/p99 = a/b/c ms | sent=100 failed=0 dlq=0`.

### 2.4 Xong khi

Chạy được script với **cùng môi trường, cùng con số lặp lại được** (2 lần chạy lệch < 10%) → đó là baseline. Mọi giai đoạn sau phải chạy lại đúng script này để chứng minh.

---

## 3. Chốt đường gửi — transport (P0, quyết định duy nhất mở trần năng suất)

### 3.1 Cơ chế & so sánh

| Tiêu chí | A — smtp.gmail.com (hiện tại) | B — Resend/SendGrid HTTP API |
|---|---|---|
| Giới hạn | ~500–2.000 mail/**ngày** (1 tài khoản) | free ~100/ngày; **trả phí: bỏ trần** |
| RTT 1 mail | 1,5–5s | 100–300ms |
| Gửi song song | 421 khi burst | rate limit rõ, retry 429 |
| Vì sao | mỗi mail = bắt tay SMTP + chờ server xếp hàng | 1 HTTP request, không giữ kết nối |

Không có tối ưu code nào vượt được trần của nhánh A — con số "30–100 mail/s" chỉ tồn tại ở nhánh B trả phí.

### 3.2 Triển khai nhánh B (không đụng outbox/consumer/handler)

**Bước 1 — tách gửi ra sau interface.** File mới trong `infrastructure/mail/`:
```java
public interface MailSender {
    void sendHtmlEmail(String to, String subject, String html);
}
```
- `SmtpMailSender implements MailSender` — **di dời nguyên code** `mailSender.createMimeMessage()...send()` từ `EmailService` sang (giữ làm fallback).
- `HttpMailSender implements MailSender` — RestClient gọi API provider, retry 429/5xx (có sẵn `spring-retry` trên classpath, hoặc vòng lặp backoff + đọc header `Retry-After`), timeout 5–10s.
- Chọn impl bằng property:
```java
@Component
@ConditionalOnProperty(name = "mail.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailSender implements MailSender { ... }

@Component
@ConditionalOnProperty(name = "mail.provider", havingValue = "http")
public class HttpMailSender implements MailSender { ... }
```
- `EmailService` giữ toàn bộ phần render template + các method `sendOtpEmail(...)`, chỉ đổi chỗ gửi cuối: field `JavaMailSender` → field `MailSender`, và `sendHtmlEmail(...)` ủy quyền cho interface. `EmailHandler` **không đổi dòng nào**.

**Bước 2 — config** (`application.yml` + `.env`):
```yaml
spring:
  mail:
    provider: ${MAIL_PROVIDER:smtp}   # smtp | http
  # khi dùng http:
  # mail.http.api-url / mail.http.api-key → property class mới MailHttpProperties
```

**Bước 3 — canary:** bật `MAIL_PROVIDER=http`, chạy batch test qua `OutboxTestController`, so metric `outbox.smtp.send` (RTT) + `outbox.email.failed` giữa 2 nhánh rồi mới cho lưu lượng thật. Có thể thử trên 1 event type bằng cách tạm đổi handler đó sang gọi thẳng `HttpMailSender` — không cần cơ chế routing phức tạp.

### 3.3 Điều kiện dừng

Chỉ có free tier (~100 mail/ngày) → **không đổi**: thấp hơn cả Gmail Workspace 2.000/ngày, thêm vendor + dependency mà không giải quyết volume. Nhánh B chỉ đáng làm khi mua gói trả phí (cần gửi > vài trăm mail/ngày thật).

---

## 4. Nới concurrency — chỉ sau khi chốt §3

### 4.1 Nhánh B

**Cơ chế:** mỗi stream worker chặn trong 1 lần gửi → **số worker = số request HTTP đang bay**. Không cần executor trung gian: tăng `app.outbox.workers` (mục 1.3) là tăng concurrency gửi, kèm Hikari pool theo công thức.

**Lưu ý chống gửi trùng:** không tách "claim nhanh → gửi qua queue riêng" — worker sẽ nhả claim PROCESSING nhưng mail chưa gửi; nếu mail nằm queue > `reclaimIdleMs` (60s), reclaimer tưởng chết → XCLAIM gửi lại → trùng. Giữ gửi đồng bộ trong `onMessage` cho tới khi số đo chứng minh cần tách.

**Triển khai:** đặt workers theo rate limit provider (ví dụ bắt đầu 8–16), theo dõi `outbox.smtp.error.421`/429 counter: tăng dần workers, counter 429 tăng → dừng ở mức đó (HttpMailSender backoff đã tự làm chậm).

### 4.2 Nhánh A

**Cơ chế:** không có connection pooling (mục 5) → mỗi mail bắt tay SMTP mới (~vài trăm ms overhead) + Gmail giới hạn kết nối đồng thời. Tăng worker mù chỉ làm 421 xuất hiện sớm hơn.

**Triển khai:**
- Giữ 8 workers; nếu muốn giảm chi phí bắt tay: 1 `SMTPTransport` kết nối dài dùng chung, các worker gửi qua hàng đợi nội bộ (1 thread gửi tuần tự — đúng bản chất Gmail). Tự lo thread-safety (`Transport.sendMessage` không an toàn đa luồng) và Gmail tự đóng khi idle.
- `batchSize` giữ 1 (xem 1.5).

### 4.3 Tiêu chí qua môn (cả 2 nhánh)

Chạy benchmark N = 100 (mục 2.3): drain đạt chỉ tiêu §1 bản cũ (A: ≤ 2 phút; B: ≤ vài giây), counter 421/429 không tăng vô hạn, không vượt quota ngày.

---

## 5. Đừng làm — và cơ chế vì sao vô ích

| Việc | Cơ chế vì sao vô ích |
|---|---|
| Cấu hình `mail.smtp.pool.*` | Property **không tồn tại** trong `org.eclipse.angus:jakarta.mail:2.0.5` (kiểm chứng javadoc package + grep bytecode jar = 0 kết quả) → bị bỏ qua âm thầm, không báo lỗi |
| Tăng `batchSize` để gửi nhanh hơn | Batch chỉ gộp lệnh XREADGROUP; container **trao từng message cho cùng 1 thread, xử lí tuần tự, chặn tới khi gửi xong** — không tăng số mail song song |
| Tăng worker mù ở nhánh A | Không có pool → mỗi lần gửi bắt tay SMTP mới; Gmail trả 421 khi quá nhiều kết nối → retry ồ ạt |
| Tách stream OTP riêng | Chỉ giúp OTP không xếp sau mail thường; **không giảm RTT từng mail** (vẫn 1,5–5s chờ Gmail). OTP < 5s end-to-end chỉ nhánh B giải quyết được |
| Scale ngang nhiều instance | Consumer group + `FOR UPDATE SKIP LOCKED` chạy được, nhưng **tổng quota vẫn của 1 tài khoản Gmail** — nhiều máy không gửi được nhiều hơn |
| Pipeline XADD cho polling scheduler | Polling ≤ 100 row/10s, đường không nóng; fast path listener đã XADD song song ~50ms/21 mail |

---

## 6. Lộ trình tổng

| Bước | Làm | Xong khi (đều đo bằng script §2.3) |
|---|---|---|
| 0 | §2 đo baseline | con số lặp lại được, có p50/p95/p99 |
| 1 | §1.2 + §1.3 + §1.4 (§1.1 = quyết định giữ check, không đổi code) | hết `findById` trên hot path (XPENDING giữ cố ý theo §1.1); workers cấu hình được; DLQ/retry không đổi (test lỗi nhân tạo) |
| 2 | §3 chốt nhánh A/B; nếu B: MailSender + HttpMailSender | chạy được cả 2 nhánh, metric tách riêng |
| 3 | §4 nới concurrency theo nhánh | burst 100 mail đạt chỉ tiêu; 421/429 không mất kiểm soát; không vượt quota |
| 4 | Tùy chọn: 1.5, stream OTP, scale ngang | theo mục tiêu riêng từng mục |

Thứ tự bắt buộc: **0 → 1 → 2 → 3**. Bước 1 làm ngay được, không chờ quyết định transport. Không tối ưu theo cảm tính — mọi bước đối chiếu baseline bước 0.
