# Kế hoạch: Module email qua Redis Stream + Transactional Outbox (v2)

**Ngày cập nhật:** 2026-08-29
**Dự án:** FINDJOB-BE
**Trạng thái:** Giai đoạn 1 (xương sống) ĐÃ HOÀN TẤT — code khớp plan. Còn lại Giai đoạn 2 (tích hợp nghiệp vụ) + Giai đoạn 3 (vận hành).

> **Cập nhật 2026-08-29 (sau khi chạy thực tế):** phát hiện & sửa 2 lỗi runtime quan trọng
> trong Giai đoạn 1:
> 1. **`markQueued` fail `TransactionRequiredException`** khi gọi từ listener — fix bằng
>    `@Async("emailTaskExecutor")` trên `onSaved` (mục 5.4) + `@Transactional` riêng cho các
>    method gọi `@Modifying` query trong `OutboxService` (mục 5.1).
> 2. **`NOGROUP` lúc start** — container `XREADGROUP` chạy trước khi consumer group được tạo;
>    fix bằng cách start container trong `@EventListener(ApplicationReadyEvent)` sau khi tạo
>    group (mục 5.6).
>
> **Cập nhật 2026-08-29 (tối ưu hiệu năng):** thêm **8 consumer song song** trong cùng group
> (`CONSUMER_NAME-w0`..`-w7`) để xử lý email song song, tăng throughput (mục 5.6). Kết quả
> thực tế: 8 email đầu xử lý song song trong ~5 giây (trước đây single-thread mất ~40 giây),
> tổng 21 email giảm ~35% thời gian, reclaimer không còn can thiệp gây duplicate.

> Bản v1 (chỉ có `PENDING/SENT/FAILED`, listener tự đặt `SENT` sau khi push) đã bị loại bỏ —
> thiết kế đó khiến consumer thấy `SENT` rồi skip mà không hề gửi mail. Bản v2 dưới đây sửa
> triệt để bằng cách thêm trạng thái `QUEUED` và quy định **chỉ consumer được đặt `SENT`**.

---

## 1. Mục tiêu & quyết định thiết kế

Mục tiêu: chuyển gửi mail từ gọi thẳng `@Async EmailService` sang hàng đợi **Redis Streams**
kèm **Transactional Outbox** để đảm bảo không mất sự kiện khi Redis/SMTP chết, có retry,
DLQ, và scale ngang consumer.

Các quyết định cốt lõi (số thứ tự được tham chiếu ở các mục sau):

1. **DB là nguồn chân truth.** Row outbox ghi **cùng transaction** với nghiệp vụ — commit
   cùng sống, rollback cùng chết. Stream chỉ là băng chuyền, mất entry còn cứu được từ DB.
2. **AFTER_COMMIT mới được phép đẩy Redis.** Đẩy trước commit mà TX rollback là gửi OTP cho
   tài khoản chưa tồn tại.
3. **4 trạng thái** `PENDING → QUEUED → SENT`, nhánh lỗi `FAILED`; `SENT` **chỉ consumer**
   được đặt, sau khi handler chạy OK.
4. **Hai đường đẩy vào stream** (fast path sau commit + polling dự phòng) chủ đích cho phép
   trùng nhau.
5. **Consumer manual-ACK** chuẩn Spring Data Redis (`receive(consumer, offset, listener)`),
   tự quyết lúc ack; consumer group tạo idempotent lúc start (BUSYGROUP = coi như xong).
6. **Chỉ giữ connection DB vài ms mỗi lần đụng DB** — không bọc call Redis trong transaction.
7. **Backoff + giới hạn số lần thử** phía push (polling) và phía consume (`deliveryCount`),
   vượt ngưỡng → DLQ + `FAILED`.
8. **Chấp nhận at-least-once delivery — handler bắt buộc idempotent.**

> [!important] Vì sao không theo đuổi exactly-once
> "Đúng 1 lần" đòi hỏi khoá hai hệ (DB + Redis + SMTP) trong một giao dịch — thực tế không hệ
> phân tán nào làm được rẻ. Ngược lại, "không mất" là yêu cầu cứng (OTP, welcome mail). Nên ta
> chọn **không-mất + chấp-nhận-trùng**, rồi xử lý trùng bằng idempotency ở handler — cách này
> rẻ và chắc chắn hơn nhiều.

---

## 2. Mô hình trạng thái

| Trạng thái | Ý nghĩa | Ai đặt |
|---|---|---|
| `PENDING` | Sự kiện **chỉ tồn tại trong DB**, chưa vào Redis. Đây là trạng thái sinh ra cùng lúc với nghiệp vụ. | Business service (insert) |
| `QUEUED` | Đã `XADD` vào Redis Stream, đang chờ consumer lấy. | Listener sau-commit hoặc polling scheduler, **sau khi XADD thành công** |
| `SENT` | Mail gửi thành công + đã `XACK`. Trạng thái kết thúc tốt đẹp — chỉ consumer được phép đặt. | Consumer |
| `FAILED` | Hết cứu cánh: hoặc đẩy lên Redis thất bại quá `max_retries`, hoặc consumer thử hoài không xong → xuống DLQ. | Polling scheduler / PendingReclaimer |

Mũi tên `QUEUED → PENDING` (qua janitor) là lưới an toàn: nếu entry trong Redis bị cắt mất
(trim/flush) nhưng row DB còn kẹt ở `QUEUED` quá 15 phút, hệ thống nghi ngờ và đẩy lại. Việc
đẩy lại có thể khiến mail gửi 2 lần — vô hại vì handler idempotent (xem mục 6).

---

## 3. Kiến trúc tổng quan

> [!tip] Tóm tắt bằng một câu: mỗi email cần gửi được lưu thành **1 row trong bảng outbox (PostgreSQL)** trước, rồi mới được đẩy sang **Redis Stream** để consumer gửi dần. Row là "nguồn sự thật" — Redis chỉ là hàng đợi tạm. Nếu Redis chết hay mất dữ liệu thì row trong DB vẫn còn, hệ thống tự đẩy lại được.

```mermaid
flowchart TD
    A[Business Service<br/>vd AuthService] -->|cùng TX| B[(PostgreSQL<br/>outbox: PENDING)]
    A -->|publish event| C[OutboxEventListener<br/>AFTER_COMMIT]
    C -->|XADD OK| D{{Redis Stream<br/>findjob:event-queue}}
    C -->|set QUEUED| B
    E[Polling Scheduler<br/>SKIP LOCKED + backoff] -->|PENDING quá hạn next_retry_at| D
    E -->|set QUEUED| B
    D --> F[EventStreamConsumer<br/>manual ACK ×8 worker]
    G[PendingReclaimer<br/>XAUTOCLAIM min-idle 60s] -->|claim lại message kẹt| D
    G -->|deliveryCount >= max| H[[findjob:event-dlq]]
    F -->|OK: XACK + SENT| B
    F --> I[EventHandlerRegistry]
    I --> J[EmailHandler<br/>render template + send]
```

**📖 Giải thích sơ đồ — đi theo thứ tự đánh số**

1. **Business Service** (ví dụ `AuthService` khi đăng ký): vừa ghi bảng nghiệp vụ, vừa ghi 1
   dòng vào bảng `outbox` — **trong cùng một transaction**. Commit xong thì cả hai cùng tồn
   tại, rollback thì cả hai cùng biến mất. Đây là điểm mấu chốt đảm bảo "không mất": không có
   tình huống "nghiệp vụ xong mà quên ghi email cần gửi".
2. **OutboxEventListener** nghe sự kiện *sau khi commit*: lập tức thử `XADD` đưa sự kiện vào
   Redis Stream. Đây là đường nhanh — email vào hàng trong vài mili-giây.
3. Push thành công → đánh dấu row thành `QUEUED`.
4. Nếu bước 2 thất bại (Redis chết): row **giữ nguyên `PENDING`**.
5. **Polling Scheduler** mỗi 10 giây quét các row `PENDING` quá hạn `next_retry_at`, đẩy lại
   vào Stream. Đây là đường dự phòng — chậm hơn nhưng bù lại không bao giờ bỏ rơi sự kiện.
6. **EventStreamConsumer** đọc message từ Stream theo cơ chế consumer group, gọi handler
   tương ứng theo `eventType`. Cùng 1 instance `@Component` được **8 consumer** (`-w0`..`-w7`)
   dùng chung để xử lý song song (mục 5.6) — phải thread-safe.
7. Handler gửi mail thật (SMTP). OK → `XACK` + row thành `SENT`.
8. **PendingReclaimer** mỗi 30 giây soi danh sách message "đã giao nhưng chưa ack" (pending
   entries): cái nào treo quá 60 giây thì nhận lại để xử lý; thử quá số lần → đẩy vào
   **DLQ** (`findjob:event-dlq`) và đánh dấu row `FAILED`.

Hai đường đẩy vào Stream (2 và 5) có thể đẩy **trùng nhau** một sự kiện — điều này là chủ
đích, consumer sẽ tự loại trùng (mục 5.8).

### 3b. Ví dụ đi qua toàn hệ thống: gửi mail OTP khi đăng ký

```mermaid
sequenceDiagram
    participant U as User (form đăng ký)
    participant Auth as AuthService
    participant DB as PostgreSQL (outbox)
    participant L as OutboxEventListener
    participant R as Redis Stream
    participant C as EventStreamConsumer
    participant EH as EmailHandler
    participant M as SMTP Server

    U->>Auth: POST /register
    Note over Auth,DB: CÙNG 1 TRANSACTION
    Auth->>DB: INSERT users (is_active=false)
    Auth->>DB: INSERT outbox {EMAIL_OTP, payload:{to,otp}, PENDING}
    Auth-->>U: 200 OK (yêu cầu nhập OTP)
    Note over Auth,DB: COMMIT ✅
    Auth--)L: publish OutboxSavedEvent
    L->>R: XADD findjob:event-queue {...}
    L->>DB: UPDATE outbox SET status=QUEUED
    R-->>C: message (XREADGROUP)
    C->>DB: SELECT outbox WHERE id=... (status != SENT?)
    C->>EH: handle(payloadJson)
    EH->>EH: render template email/otp (username, otp)
    EH->>M: send HTML mail
    EH-->>C: OK
    C->>DB: UPDATE outbox SET status=SENT
    C->>R: XACK
```

**📖 Giải thích:** người dùng chỉ cảm nhận bước đầu và cuối — submit form rồi mở mailbox.
Mọi bước giữa diễn ra nền, không chặn API. Nếu Redis chết tại bước `XADD`: response API vẫn
trả bình thường (outbox đã commit), mail đi muộn hơn qua polling — người dùng nhận OTP chậm
vài chục giây thay vì không nhận gì.

---

## 4. Schema — Flyway `V16__create_outbox_table.sql`

> [!note] Migration mới nhất hiện tại là V15 → file này bắt buộc tên `V16__...`.
> File đã tồn tại sẵn trong repo: `src/main/resources/db/migration/V16__create_outbox_table.sql`.

```sql
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50),
    aggregate_id  BIGINT,
    payload       JSONB NOT NULL,                 -- {to, templateName, variables{...}}
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|QUEUED|SENT|FAILED
    retry_count   INT NOT NULL DEFAULT 0,
    max_retries   INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index nóng: tập PENDING luôn nhỏ
CREATE INDEX idx_outbox_pending ON outbox (next_retry_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_status_queued ON outbox (created_at) WHERE status = 'QUEUED';
CREATE INDEX idx_outbox_status_sent_created ON outbox (status, created_at);
```

**📖 Giải thích schema — chia 4 nhóm cột:**

- **Định danh & truy vết:** `id` (`BIGSERIAL` = số tự tăng, map sang `Long`; v1 từng dùng UUID nhưng không cần thiết), `event_type` (quyết định handler nào xử lý — "loại thư"), `aggregate_type`/`aggregate_id` (sự kiện này thuộc đối tượng nghiệp vụ nào, vd `USER` #42 — chỉ để debug/truy vết, logic không phụ thuộc).
- **Hành lý:** `payload` (JSONB) gói mọi thứ handler cần (`to`, `templateName`, `variables`). Nguyên tắc *self-contained*: consumer KHÔNG quay lại bảng users/jobs để hỏi thêm — đề phòng dữ liệu gốc đã đổi làm payload stale.
- **Vòng đời & retry:** `status` điều phối máy trạng thái; `retry_count`/`max_retries` giới hạn số lần thử; `next_retry_at` là mốc "hẹn giờ thử lại" — nền tảng của backoff; `last_error` lưu vết lỗi cuối để soi log không phải đoán.
- **Mốc thời gian:** `updated_at` ngoài mục đích thường quy còn là dấu vết janitor dựa vào để biết row "kẹt QUEUED đã lâu chưa".

**📖 Hai cột `retry_count` + `max_retries` — bộ đếm và giới hạn:**

- `retry_count` = row này **đã đẩy vào Redis thất bại bao nhiêu lần**. Mỗi lần polling đẩy mà hỏng (Redis chết, mất kết nối...) thì +1. Row mới tạo thì bằng 0.
- `max_retries` = **giới hạn số lần thử**: fail đủ số lần này thì chuyển `FAILED` và thôi không thử nữa. Mặc định 5; vì là cột riêng từng row nên event nào quan trọng hơn có thể set số lớn hơn lúc tạo.

Ví dụ cụ thể: có 1 email OTP cần gửi mà Redis sập luôn, polling cứ thử lại theo giờ hẹn:

| Diễn biến | retry_count | next_retry_at (hẹn thử lại lúc) | status |
|---|---|---|---|
| Vừa ghi row | 0 | *(rỗng → được nhặt ngay)* | PENDING |
| Đẩy vào Redis hỏng lần 1 | 1 | sau 30 giây | PENDING |
| Hỏng lần 2 | 2 | sau 1 phút | PENDING |
| Hỏng lần 3 | 3 | sau 2 phút | PENDING |
| Hỏng lần 4 | 4 | sau 4 phút | PENDING |
| Hỏng lần 5 = đủ max_retries | 5 | *(không hẹn nữa)* | FAILED 💀 |

Giờ hẹn xa dần gấp đôi mỗi lần (30s → 1p → 2p...) gọi là *backoff* — tránh việc Redis đang chết mà polling dội liên tục mỗi 10 giây. Câu SELECT lấy row cũng có điều kiện `retry_count < max_retries`, nên row FAILED tự động bị bỏ qua mãi mãi.

Lưu ý ranh giới: hai cột này **chỉ đếm lỗi đẩy vào Redis** (đường polling). Riêng phía consumer gửi mail thất bại thì không đụng vào đây — nó dùng `deliveryCount` (số lần Redis tự giao lại message trong PEL) so với chính `max_retries` này để quyết định thử lại hay đưa xuống DLQ (mục 5.8).

Ba index cuối là **partial index**: `idx_outbox_pending` chỉ đánh chỉ số các row `PENDING` — vốn luôn rất ít (chỉ sự kiện chờ đẩy) → query polling mỗi 10 giây quét index nhỏ xíu, gần như miễn phí.

### 4.1 Entity + Enum

Package `com.example.boilerplate.common.outbox.entity` — `@Entity Outbox` map 1-1 bảng trên
(`id` kiểu `Long` vì `BIGSERIAL`), enum `OutboxStatus { PENDING, QUEUED, SENT, FAILED }`.

```java
// common/outbox/entity/Outbox.java
// imports: jakarta.persistence.*, lombok.*, org.hibernate.annotations.*, java.time.Instant
@Entity
@Table(name = "outbox")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // khớp BIGSERIAL — DB tự cấp số
    private Long id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    /**
     * Payload giữ nguyên dạng chuỗi JSON (không map sang object Java).
     * Kết hợp @JdbcTypeCode(SqlTypes.JSON) để Hibernate 6 ghi chuỗi này
     * vào đúng kiểu jsonb của PostgreSQL.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

```java
// common/outbox/entity/OutboxStatus.java
public enum OutboxStatus { PENDING, QUEUED, SENT, FAILED }
```

**📖 Giải thích đơn giản:** đoạn trên chỉ là cách "gói" bảng outbox thành một class Java để code thao tác thay vì viết SQL tay:

- `@Entity` + `@Table`: báo cho Spring biết "class này ứng với bảng outbox trong DB".
- `@Id` + `@GeneratedValue(IDENTITY)`: cột id để DB tự đánh số tăng dần, code không cần tự đặt.
- `@Column(name = ...)`: nối tên biến Java với tên cột trong DB (vd `eventType` ↔ cột `event_type`).
- `@JdbcTypeCode(SqlTypes.JSON)`: cột payload trong DB là kiểu JSON, annotation này giúp Spring gửi chuỗi JSON vào đúng kiểu đó.
- `@Enumerated(EnumType.STRING)`: status lưu trong DB dạng chữ (`PENDING`, `QUEUED`…) chứ không phải con số.
- `@Builder.Default`: nếu tạo object mà quên set `retryCount`/`maxRetries` thì tự dùng giá trị mặc định 0 và 5.
- Hai annotation timestamp cuối: Hibernate tự điền giờ tạo / giờ sửa, khỏi viết tay.

Còn enum `OutboxStatus` ở dưới chỉ là danh sách 4 trạng thái để code so sánh cho dễ đọc (`status == SENT` thay vì so chuỗi `"SENT"`).

### 4.2 Repository — các query then chốt

Package `com.example.boilerplate.common.outbox.repository`. Khung interface đầy đủ:

```java
// common/outbox/repository/OutboxRepository.java
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    // findById(id) — kế thừa sẵn, consumer dùng để tra trạng thái chống trùng

    // ① Polling scheduler lấy một mẻ row PENDING đem đi đẩy vào Redis
    List<Outbox> lockPendingBatch(int limit);
    // ② Chuyển row PENDING → QUEUED, gọi ngay sau khi XADD vào Redis thành công
    int markQueued(Long id);
    // ③ Janitor cứu row kẹt ở QUEUED quá lâu: kéo về PENDING để polling đẩy lại
    int requeueStaleQueued(int minutes);
    // ④ Ghi nhận 1 lần đẩy Redis thất bại: +1 retry_count + hẹn giờ thử lại; hết lượt → FAILED
    int registerPushFailure(Long id, String error);
    // ⑤ CHỈ consumer được gọi — sau khi mail gửi thật sự thành công, chuyển QUEUED → SENT
    int markSent(Long id);
    // ⑥ Consumer ghi nội dung lỗi vào cột last_error để debug
    void noteProcessingError(Long id, String error);
    // ⑦ Reclaimer gọi sau khi message đã vào DLQ: đóng hồ sơ row là FAILED
    int markFailed(Long id, String reason);
}
```

**📖 Đừng vội đọc SQL — hãy xem 1 row đi qua các query này như thế nào trước.**

Bảng outbox là **danh sách các email cần gửi**. Mỗi row tương ứng 1 email. Ví dụ user vừa đăng ký tài khoản, hệ thống cần gửi mail OTP — row #42 chính là cái email OTP đó:

| id | event_type | status | retry_count | next_retry_at |
|----|------------|--------|-------------|---------------|
| 42 | EMAIL_OTP | PENDING | 0 | *(rỗng)* |

Nghĩa của cột status với từng giá trị: `PENDING` = chưa đẩy vào Redis · `QUEUED` = đã nằm trong Redis, chờ consumer gửi · `SENT` = đã gửi thành công · `FAILED` = hỏng quá nhiều lần, bỏ cuộc.

Cuộc đời bình thường của row #42:

```
[AuthService ghi row này cùng lúc với việc tạo tài khoản]
        ↓   status = PENDING   («email đã được ghi nhận, chưa ai xử lý»)
[Polling scheduler lấy row, đẩy vào Redis thành công]
        ↓   markQueued   →   status = QUEUED   («email đã nằm trong hàng đợi Redis»)
[Consumer lấy ra từ Redis, gọi SMTP gửi mail OK]
            markSent   →   status = SENT ✅   («mail tới tay người dùng — xong»)
```

Các nhánh xấu:

```
[Đẩy vào Redis hỏng]              → registerPushFailure: cộng thêm 1 lần hỏng,
                                    hẹn giờ thử lại xa dần (30s → 1p → 2p…);
                                    hỏng đủ max_retries lần → FAILED 💀

[Row QUEUED mà 15 phút không nhúc nhích] → requeueStaleQueued: nghi bản
                                    trong Redis đã mất, trả về PENDING để đẩy lại

[Consumer xử lý mãi không xong]   → markFailed: message xuống DLQ,
                                    đóng hồ sơ FAILED
```

Như vậy — **mỗi query chỉ là một câu UPDATE/SELECT đổi trạng thái của row**. Bảng tóm tắt:

| Query | Ai gọi, khi nào | Nó làm gì với row |
|---|---|---|
| `lockPendingBatch` | Polling scheduler, mỗi 10 giây | Lấy ra tối đa 100 row PENDING để đem đẩy lên Redis |
| `markQueued` | Ngay sau khi XADD thành công | PENDING → QUEUED |
| `registerPushFailure` | Khi đẩy vào Redis hỏng | Vẫn PENDING, nhưng +1 lần hỏng + hẹn giờ thử lại; hết lượt → FAILED |
| `requeueStaleQueued` | Janitor, đầu mỗi vòng polling | QUEUED → PENDING (nghi mất bản trong Redis) |
| `markSent` | Consumer, sau khi mail đi thật | QUEUED → SENT ✅ |
| `noteProcessingError` | Consumer, khi handler báo lỗi | Chỉ ghi chú vào cột last_error |
| `markFailed` | Reclaimer, sau khi message vào DLQ | QUEUED → FAILED |

Chỉ còn một chỗ hơi rối: các điều kiện trong câu SELECT ①. Dịch nôm na từng dòng:

- `WHERE status = 'PENDING'` → chỉ lấy row CHƯA đẩy vào Redis
- `AND retry_count < max_retries` → loại những row đã hỏng quá số lần cho phép
- `AND (next_retry_at IS NULL OR next_retry_at <= now())` → row mới (chưa có giờ hẹn) hoặc đã tới giờ hẹn thì lấy; đang chờ giờ hẹn tương lai thì bỏ qua lần poll này
- `ORDER BY created_at LIMIT :limit` → email cũ nhất gửi trước, tối đa 100 row/lần
- `FOR UPDATE SKIP LOCKED` → khoá các row vừa lấy; instance backend khác chạy cùng lúc thấy row đang khoá thì nhảy qua, lấy row tiếp theo → hai bên chia nhau batch, không ai giành của ai

Code đầy đủ của cả 7 query nằm ngay dưới đây — giờ đọc sẽ thấy chúng chẳng làm gì ngoài những việc trong bảng trên:

```java
// common/outbox/repository/OutboxRepository.java — thân các @Query
/** Lịch chạy 10 giây/lần gọi hàm này để LẤY MỘT MẺ row PENDING đem đi đẩy vào Redis.
 *  - FOR UPDATE: khoá tạm mấy row vừa lấy → instance backend khác không lấy trùng
 *  - SKIP LOCKED: gặp row đang bị instance khác khoá thì bỏ qua, lấy row kế tiếp
 *    → chạy 2 instance song song thì mỗi bên nhận một phần mẻ
 *  - Khoá chỉ sống tới khi transaction này commit (~vài ms) — sau đó 100 row đã
 *    nằm sẵn trong bộ nhớ Java, xử lý tiếp không cần giữ khoá/connection gì nữa */
@Query(value = """
    SELECT * FROM outbox
    WHERE status = 'PENDING'
      AND retry_count < max_retries
      AND (next_retry_at IS NULL OR next_retry_at <= now())
    ORDER BY created_at
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
""", nativeQuery = true)
List<Outbox> lockPendingBatch(@Param("limit") int limit);

/** Chuyển row từ PENDING sang QUEUED — gọi ngay sau khi XADD vào Redis thành công.
 *  Điều kiện WHERE status='PENDING': chỉ đổi khi row còn PENDING; nếu người khác
 *  đổi trước rồi thì câu lệnh chẳng ảnh hưởng dòng nào (trả về 0). */
@Modifying
@Query(value = """
    UPDATE outbox SET status = 'QUEUED', next_retry_at = NULL, last_error = NULL
    WHERE id = :id AND status = 'PENDING'
""", nativeQuery = true)
int markQueued(@Param("id") Long id);

/** Dọn dẹp định kỳ: row đang QUEUED mà hơn N phút không nhúc nhích thì trả về PENDING.
 *  Tại sao? QUEUED nghĩa là "đã nằm trong Redis" — nếu bản trong Redis bị mất
 *  (Redis bị xoá dữ liệu, entry bị cắt bớt...) thì row này kẹt mãi mãi,
 *  nên kéo về PENDING cho polling đẩy lại. */
@Modifying
@Query(value = """
    UPDATE outbox SET status = 'PENDING', next_retry_at = now()
    WHERE status = 'QUEUED' AND updated_at < now() - (:minutes * interval '1 minute')
""", nativeQuery = true)
int requeueStaleQueued(@Param("minutes") int minutes);

/** Gọi khi đẩy vào Redis THẤT BẠI. Một câu UPDATE làm gọn 3 việc:
 *  cộng 1 vào retry_count, lưu nội dung lỗi, đặt giờ thử lại xa dần
 *  (30 giây → 1 phút → 2 phút... tối đa 10 phút) để polling hẹn giờ thử lại,
 *  không dồn dập đánh liên tục. Hỏng đủ max_retries lần thì chuyển FAILED. */
@Modifying
@Query(value = """
    UPDATE outbox
    SET retry_count = retry_count + 1,
        last_error = :error,
        next_retry_at = CASE WHEN retry_count + 1 >= max_retries THEN NULL
                             ELSE now() + least(power(2, retry_count) * interval '30 seconds',
                                                interval '10 minutes') END,
        status = CASE WHEN retry_count + 1 >= max_retries THEN 'FAILED' ELSE status END
    WHERE id = :id AND status = 'PENDING'
""", nativeQuery = true)
int registerPushFailure(@Param("id") Long id, @Param("error") String error);
```

#### 4.2b Query phía consume (⑤⑥⑦)

```java
// common/outbox/repository/OutboxRepository.java (tiếp)
// ── Consumer & Reclaimer dùng thêm ─────────────────────────────────────────────

/** ⑤ Consumer gọi SAU KHI mail gửi THẬT SỰ thành công.
 *  Điều kiện status <> 'SENT': nếu row đã SENT rồi thì câu lệnh không đổi gì cả
 *  — nhờ vậy lỡ gọi 2 lần cũng vô hại. */
@Modifying
@Query("UPDATE Outbox o SET o.status = 'SENT', o.lastError = NULL"
       + " WHERE o.id = :id AND o.status <> 'SENT'")
int markSent(@Param("id") Long id);

/** ⑥ Ghi chú lỗi gần nhất vào cột last_error để sau này debug.
 *  Không đụng tới bộ đếm retry — phía Redis đã có deliveryCount tự tăng
 *  mỗi lần giao lại message rồi, DB khỏi đếm kép. */
@Modifying
@Query("UPDATE Outbox o SET o.lastError = :error WHERE o.id = :id")
void noteProcessingError(@Param("id") Long id, @Param("error") String error);

/** ⑦ Reclaimer gọi sau khi message đã bị ném vào DLQ: đóng hồ sơ row là FAILED.
 *  Điều kiện status <> 'SENT' đảm bảo không bao giờ ghi đè lên kết quả
 *  thành công của consumer. */
@Modifying
@Query("UPDATE Outbox o SET o.status = 'FAILED', o.lastError = :reason"
       + " WHERE o.id = :id AND o.status <> 'SENT'")
int markFailed(@Param("id") Long id, @Param("reason") String reason);
```

**📖 Giải thích đơn giản 3 query phía consume:**

- **⑤ `markSent(id)`** — consumer gọi sau khi mail gửi THẬT SỰ thành công: `{status: QUEUED}` → `{status: SENT}`. Điều kiện `status <> 'SENT'`: nếu đã SENT rồi thì câu lệnh vô hại (không đổi dòng nào) — nhờ vậy gọi nhầm 2 lần cũng không sao.
- **⑥ `noteProcessingError(id, error)`** — chỉ ghi chú lỗi gần nhất vào `last_error`, ví dụ `{last_error: "SMTP timeout"}`, để sau này debug. Không đụng bộ đếm retry vì phía Redis đã có `deliveryCount` tự tăng mỗi lần giao lại message.
- **⑦ `markFailed(id, reason)`** — reclaimer gọi sau khi message đã bị ném vào DLQ: `{status: QUEUED}` → `{status: FAILED}`. Điều kiện `<> 'SENT'` đảm bảo không bao giờ ghi đè lên kết quả thành công.

---

## 5. Các thành phần chi tiết

**🗺️ Bản đồ file & package — code mới sẽ nằm ở đây:**

| File | Package đầy đủ |
|---|---|
| `Outbox.java`, `OutboxStatus.java` | `com.example.boilerplate.common.outbox.entity` |
| `OutboxRepository.java` | `com.example.boilerplate.common.outbox.repository` |
| `OutboxSavedEvent.java`, `OutboxEventListener.java` | `com.example.boilerplate.common.outbox.event` |
| `EventStreamProducer.java` | `com.example.boilerplate.common.outbox.producer` |
| `OutboxService.java` | `com.example.boilerplate.common.outbox.service` |
| `OutboxPollingScheduler.java` | `com.example.boilerplate.common.outbox.scheduler` |
| `OutboxStreamConfig.java`, `OutboxStreamProperties.java` | `com.example.boilerplate.common.outbox.config` |
| `EventStreamConsumer.java` | `com.example.boilerplate.common.outbox.consumer` |
| `PendingReclaimer.java` | `com.example.boilerplate.common.outbox.reclaimer` |
| `EventHandler.java`, `EventHandlerRegistry.java`, `EmailHandler.java` | `com.example.boilerplate.common.outbox.handler` |

> Các đoạn code dưới đây mở đầu bằng comment dạng `// common/outbox/...` — đó là đường dẫn tính từ `src/main/java/com/example/boilerplate/`.

### 5.1 Ghi outbox + phát sự kiện — `OutboxService` + `OutboxSavedEvent`

```java
// common/outbox/event/OutboxSavedEvent.java
public record OutboxSavedEvent(Long outboxId, Outbox outbox) {}
```

```java
// Ví dụ gọi thật: features/auth/service/impl/AuthServiceImplement.java, trong hàm register()
// Business service dùng — BÊN TRONG transaction nghiệp vụ:
Outbox saved = outboxService.savePending("EMAIL_OTP", "USER", userId,
        Map.of("to", email, "templateName", "email/otp",
               "variables", Map.of("username", username, "otp", otp)));
eventPublisher.publishEvent(new OutboxSavedEvent(saved.getId(), saved));
```

> [!warning] Bẫy kinh điển của `@TransactionalEventListener`
> `publishEvent` phải diễn ra **trong một transaction đang mở**, nếu không listener
> AFTER_COMMIT **không bao giờ được gọi** (mặc định `fallbackExecution = false`) — mail im
> lặng không gửi, không lỗi. Nhớ viết integration test cho case này.

`savePending` serialize payload → JSON, insert với `status=PENDING`, `retry_count=0`,
`max_retries=5`, `next_retry_at=NULL`. Lỗi serialize là bug lập trình → quăng luôn cho TX
nghiệp vụ rollback (đừng nuốt).

Class hoàn chỉnh:

```java
// common/outbox/service/OutboxService.java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional                       // các câu UPDATE cần transaction — khai báo ở class cho gọn
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    /** ① savePending — gọi BÊN TRONG transaction nghiệp vụ (mục 5.1):
     *  biến dữ liệu mail thành JSON rồi INSERT một dòng mới ở trạng thái PENDING.
     *  Nếu biến JSON thất bại (bug lập trình) thì ném lỗi ngay để cả transaction
     *  nghiệp vụ rollback — tuyệt đối không nuốt lỗi rồi âm thầm mất mail. */
    public Outbox savePending(String eventType, String aggregateType, Long aggregateId,
                              Map<String, Object> payload) {
        try {
            return repository.save(Outbox.builder()
                    .eventType(eventType)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .maxRetries(5)
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize payload failed: " + eventType, e);
        }
    }

    /**
     * Đánh dấu Outbox event đã được đưa vào Redis Stream (chuyển PENDING → QUEUED).
     * Cả fast-path (listener chạy sau commit) lẫn polling scheduler đều gọi hàm này,
     * ngay sau khi XADD vào Redis thành công.
     *
     * @param id ID của Outbox event
     * @return true nếu update thành công,
     *         false nếu không có bản ghi nào được update (row không còn ở trạng thái PENDING)
     *
     * @Transactional riêng (không dựa vào class-level): method này được gọi từ
     * @TransactionalEventListener(AFTER_COMMIT) — lúc đó transaction của request đã commit
     * & đóng, class-level @Transactional không mở TX mới được. Nếu thiếu annotation này,
     * @Modifying UPDATE query chạy không có transaction → TransactionRequiredException
     * "Executing an update/delete query".
     */
    @Transactional
    public boolean markQueued(Long id) { return repository.markQueued(id) > 0; }

    /**
     * Đánh dấu email đã được gửi thành công (chuyển QUEUED → SENT).
     * CHỈ consumer được gọi hàm này, và chỉ sau khi handler gửi mail thật sự OK —
     * bắt buộc gọi TRƯỚC khi XACK Redis (lý do của thứ tự này xem mục 5.7).
     *
     * @param id ID của Outbox event
     * @return true nếu update thành công,
     *         false nếu row đã ở trạng thái SENT trước đó (gọi trùng — vô hại)
     *
     * @Transactional riêng: consumer gọi từ thread riêng (cTaskExecutor), không có
     * transaction context sẵn — cần TX cho @Modifying UPDATE query.
     */
    @Transactional
    public boolean markSent(Long id) { return repository.markSent(id) > 0; }

    /**
     * Ghi nội dung lỗi gần nhất của consumer vào cột last_error để tiện debug.
     * Không tăng bất kỳ bộ đếm retry nào — phía Redis đã có deliveryCount tự tăng
     * mỗi lần giao lại message, DB không cần đếm kép.
     *
     * @param id    ID của Outbox event
     * @param error Nội dung lỗi handler gặp phải (vd "SMTP timeout")
     *
     * @Transactional riêng: consumer gọi từ thread riêng, cần TX cho @Modifying.
     */
    @Transactional
    public void noteProcessingError(Long id, String error) {
        repository.noteProcessingError(id, error);
    }

    /**
     * Ghi nhận một lần đẩy vào Redis THẤT BẠI — polling scheduler gọi khi push ném exception.
     * Toàn bộ logic gói trong 1 câu UPDATE bên repository (chi tiết SQL xem mục 4.2):
     * +1 retry_count, lưu last_error, đặt giờ thử lại xa dần theo backoff (30s → 1p → 2p…);
     * nếu đã thử đủ max_retries lần thì chuyển luôn sang FAILED.
     *
     * @param id    ID của Outbox event
     * @param error Nội dung lỗi khi đẩy vào Redis (thường là ex.getMessage())
     *
     * @Transactional riêng: polling scheduler gọi từ thread scheduling-1, không có
     * transaction context sẵn — cần TX cho @Modifying UPDATE query.
     */
    @Transactional
    public void registerPushFailure(Long id, String error) {
        repository.registerPushFailure(id, error);
    }

    /**
     * Lấy một mẻ row PENDING đem đi đẩy vào Redis — polling scheduler gọi mỗi 10 giây.
     * Transaction chỉ mở đúng thời gian hàm này chạy: khoá các row vừa lấy rồi commit
     * ngay khi trả về → connection DB được nhả trong vài ms,
     * không bị giữ suốt lúc gọi Redis ở vòng for bên ngoài.
     *
     * @param limit Số row tối đa mỗi lần lấy (config batch-size, mặc định 100)
     * @return Danh sách row PENDING (đã khoá FOR UPDATE SKIP LOCKED), có thể rỗng
     */
    @Transactional
    public List<Outbox> lockPendingBatch(int limit) { return repository.lockPendingBatch(limit); }

    /**
     * Janitor: chuyển row QUEUED bị kẹt quá N phút về PENDING (mục 5.2).
     * Polling scheduler gọi đầu mỗi vòng. Phải có transaction vì là @Modifying @Query (UPDATE).
     *
     * @param minutes Số phút row QUEUED im lặng thì nghi ngờ mất bản trong Redis
     * @return Số row đã được đưa về PENDING
     */
    @Transactional
    public int requeueStaleQueued(int minutes) { return repository.requeueStaleQueued(minutes); }
}
```

**📖 Giải thích cơ chế "ghi vé + phát còi":**

- **Ghi vé** (`savePending`, trong transaction): business service chỉ nói chuyện với DB — insert 1 dòng "việc cần làm". Transaction rollback thì dòng tự biến mất theo → không bao giờ sinh "mail ma" cho dữ liệu chưa tồn tại.
- **Phát còi** (`publishEvent`): KHÔNG phải gọi Redis! Chỉ là bắn một Spring event nội bộ mang theo snapshot row. Người phản ứng tiếng còi (listener AFTER_COMMIT) sẽ chạy **sau khi commit**, và business service mãi mãi không cần biết phía sau có Redis hay gì khác — sau này đổi sang Kafka cũng chỉ phải sửa đúng tầng outbox.

> [!note] Vì sao các method gọi `@Modifying` query cần `@Transactional` riêng (thêm 2026-08-29)
> `OutboxService` có `@Transactional` class-level, nhưng các method `markQueued`, `markSent`,
> `noteProcessingError`, `registerPushFailure`, `requeueStaleQueued` được gọi từ **thread
> không có transaction context sẵn** (listener `@Async` trên Virtual Thread, consumer trên
> `cTaskExecutor`, scheduler trên `scheduling-*`). Class-level `@Transactional` không đủ tin
> cậy trong các ngữ cảnh này — phải khai báo `@Transactional` riêng trên từng method để đảm
> bảo `@Modifying` UPDATE query luôn chạy trong transaction.

### 5.2 Janitor — cứu row kẹt `QUEUED`

Chạy đầu mỗi vòng polling (xem 5.5, bước ①): gọi `outboxService.requeueStaleQueued(staleQueuedMinutes)`
với mặc định 15 phút. Method này nằm trong `OutboxService` (mục 5.1) — gọi qua service vì
`requeueStaleQueued` là `@Modifying @Query` cần transaction. Xử lý các case: Redis bị `FLUSHALL`, entry bị MAXLEN trim khi ứ đọng,
consumer group bị xoá nhầm… Row quay về `PENDING` → polling đẩy lại. Có thể trùng — chấp
nhận, xem mục 6.

### 5.3 Đường đẩy vào Stream — `EventStreamProducer`

```java
// common/outbox/producer/EventStreamProducer.java
@Component
@RequiredArgsConstructor
public class EventStreamProducer {

    private final StringRedisTemplate redis;
    private final OutboxStreamProperties props;

    /** Đẩy sự kiện vào Redis Stream (chính là lệnh XADD). Trả về true nếu thành công.
     *  Redis chết thì hàm này ném lỗi — việc bắt lỗi là của người gọi, vì cần phân biệt
     *  2 trường hợp khác nhau:
     *  - đẩy hỏng → row giữ nguyên PENDING, chờ polling thử lại sau
     *  - đẩy OK nhưng ghi nhận DB hỏng → event đã chắc chắn trong hàng, tệ nhất là bị trùng */
    public boolean push(Outbox outbox) {
        Map<String, String> fields = new HashMap<>();
        fields.put("outboxId", outbox.getId().toString());
        fields.put("eventType", outbox.getEventType());
        if (outbox.getAggregateType() != null) fields.put("aggregateType", outbox.getAggregateType());
        if (outbox.getAggregateId() != null) fields.put("aggregateId", outbox.getAggregateId().toString());
        fields.put("payload", outbox.getPayload());

        // MAXLEN là tuỳ chọn của lệnh XADD (không phải thuộc tính của record):
        // truyền qua XAddOptions.maxlen(n).approximateTrimming(true) = XADD MAXLEN ~
        return redis.opsForStream().add(
                StreamRecords.string(fields).withStreamKey(props.streamKey()),
                RedisStreamCommands.XAddOptions.maxlen(props.maxlen())
                        .approximateTrimming(true)) != null;
    }
}
```

> [!warning] MAXLEN là cơ chế phòng thủ, không phải bảo chứng
> Trim xấp xỉ (`~`) có thể cắt entry **chưa ai consume** nếu ứ đọng quá 50k. Khi đó row DB
> vẫn ở `QUEUED` → janitor (mục 5.2) phát hiện và đẩy lại. DB là nơi lưu chân truth, Stream
> chỉ là băng chuyền.

**📖 Giải thích:**

- Một entry trong stream ≈ một Map nhỏ `field → value`. Ta đóng gói tối thiểu: `outboxId` (chìa khoá tra DB), `eventType` (chọn handler), `payload` (nguyên văn JSON từ DB), `aggregate*` chỉ để truy vết. Không nhét object Java vào — mọi thứ là String thuần để deserialize không bao giờ lệch.
- Vì sao `push()` **trả boolean thay vì quăng lỗi**: caller cần phân biệt 2 kiểu thất bại hoàn toàn khác nhau (kỹ hơn ở mục 6) — *Redis fail* → giữ PENDING, an toàn tuyệt đối; *push OK nhưng mark DB fail* → sự kiện đã chắc chắn nằm trong hàng, hậu quả tệ nhất là TRÙNG chứ không phải MẤT.
- Giới hạn MAXLEN **50000** không đặt trên record mà là **tuỳ chọn của lệnh XADD**: `opsForStream().add(record, XAddOptions.maxlen(maxlen).approximateTrimming(true))`. `approximateTrimming(true)` = trim xấp xỉ (`XADD MAXLEN ~`) — Redis cắt cả khối cho rẻ thay vì đếm chính xác từng entry. Lưu ý: `withMaxlen(...)` gắn trực tiếp lên `StreamRecords` là API **không tồn tại** — đừng viết vậy.

### 5.4 Sau-commit push — `OutboxEventListener`

`OutboxEventListener` là một **`@Component`** (bean Spring thường, không phải scheduler, không
phải config). Nó đăng ký nghe `OutboxSavedEvent` qua `@TransactionalEventListener(AFTER_COMMIT)`:
business service gọi `publishEvent` trong transaction → Spring ghi nhớ → ngay sau COMMIT mới
gọi method `onSaved`. Lưu ý bắt buộc: annotation này **chỉ hoạt động trên bean do Spring quản
lý** — nếu `new OutboxEventListener()` tay thì listener im lặng không bao giờ chạy.

> [!important] Vì sao `onSaved` phải có `@Async("emailTaskExecutor")` (thêm 2026-08-29)
> `@TransactionalEventListener(AFTER_COMMIT)` mặc định chạy **đồng bộ trên thread của request**
> (`nio-8080-exec-1`). Lúc đó transaction vừa commit nhưng **transaction synchronization vẫn
> còn active** trên thread đó (Spring lưu synchronization trong `ThreadLocal` của thread chạy
> transaction, chưa cleanup ngay sau commit).
>
> Khi listener gọi `outboxService.markQueued()` (cần mở TX mới cho `@Modifying` UPDATE query),
> Spring kiểm tra `ThreadLocal` → thấy synchronization còn active → tưởng "đã trong transaction"
> → **không mở TX mới** → `@Modifying` query chạy không có transaction → ném
> `TransactionRequiredException: Executing an update/delete query`.
>
> **Fix:** `@Async("emailTaskExecutor")` đưa listener sang **Virtual Thread riêng** (`virtual-XXX`)
> — thread này **không có transaction synchronization** (sạch) → `markQueued()` mở TX mới bình
> thường → hết lỗi. Đây cũng là lý do `OutboxService.markQueued` cần `@Transactional` riêng
> (mục 5.1): dù chạy trên thread sạch, vẫn phải khai báo TX cho method gọi `@Modifying` query.
>
> **Bằng chứng thực tế:** trước fix, 21/21 `markQueued` fail với `Executing an update/delete
> query` (chạy trên `nio-8080-exec-1`); sau fix, 21/21 thành công (`Outbox X queued ngay sau
> commit` chạy trên `virtual-XXX`). Polling scheduler không bao giờ gặp lỗi này vì chạy trên
> thread `scheduling-*` (không có transaction synchronization).

```java
// common/outbox/event/OutboxEventListener.java
// imports: lombok.extern.slf4j.Slf4j, org.springframework.scheduling.annotation.Async,
// org.springframework.stereotype.Component,
// org.springframework.transaction.event.* (TransactionalEventListener + TransactionPhase)
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final EventStreamProducer streamProducer;
    private final OutboxService outboxService;

    /**
     * Nghe sự kiện "đã lưu 1 row outbox" và đẩy vào Redis Stream (đường nhanh).
     * AFTER_COMMIT = chỉ chạy khi transaction nghiệp vụ ĐÃ commit thành công.
     * Vì sao quan trọng: nếu đẩy mail TRƯỚC commit mà transaction sau đó rollback
     * → consumer gửi OTP cho một tài khoản chưa bao giờ tồn tại!
     *
     * @Async("emailTaskExecutor"): chạy trên Virtual Thread riêng để thoát khỏi
     * transaction synchronization của request thread — nếu không, markQueued() bên
     * dưới không mở được TX mới → TransactionRequiredException (xem giải thích trên).
     */
    @Async("emailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaved(OutboxSavedEvent e) {
        try {
            if (streamProducer.push(e.outbox())) {
                markQueuedWithRetry(e.outboxId());   // đường nhanh: vào hàng trong vài ms
            }
        } catch (Exception ex) {
            // Redis chết/không kết nối nổi → KHÔNG ném lỗi ra request của user,
            // giữ PENDING và im lặng để polling xử lý sau (đường chậm ở 5.5)
            log.warn("Push sau commit thất bại — polling sẽ xử lý: {}", e.outboxId());
        }
    }

    /** Tách riêng lỗi đánh dấu DB khỏi lỗi push: XADD đã OK thì sự kiện chắc chắn
     *  trong hàng — mark fail chỉ gây TRÙNG (polling đẩy lại), không gây MẤT.
     *  Thử nhanh vài lần vì lỗi DB lúc này thường là transient. */
    private void markQueuedWithRetry(Long id) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // markQueued trả về true nếu đổi PENDING → QUEUED thành công;
                // trả false nghĩa là người khác đã đổi trạng thái trước rồi — thôi can thiệp
                if (outboxService.markQueued(id)) {
                    log.info("Outbox {} queued ngay sau commit", id);
                }
                return;
            } catch (Exception ex) {
                log.warn("Mark QUEUED fail (lần {}/3) outbox {}: {}", attempt, id, ex.getMessage());
                try {                          // backoff ngắn, không làm chậm request đáng kể
                    Thread.sleep(attempt * 200L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();   // khôi phục cờ interrupt rồi thoát
                    break;
                }
            }
        }
        log.error("Không mark QUEUED được outbox {} — polling sẽ đẩy lại (chấp nhận trùng)", id);
    }
}
```

**Thứ tự push-trước-mark là cố ý** — phân tích đầy đủ ở mục 6.

**📖 Giải thích ba kịch bản của listener:**

1. **Êm đẹp** (99,9% số request): XADD OK → mark QUEUED, tổng công vài ms — mail vào hàng gần như tức thì. *(Nhờ `@Async`, listener chạy trên Virtual Thread riêng nên không chặn thread request, và `markQueued` mở TX mới bình thường — không còn lỗi `TransactionRequiredException`.)*
2. **Redis chết:** catch im lặng, row ở lại PENDING. Request của user vẫn 200 bình thường; 10 giây sau polling lo tiếp. User chỉ cảm nhận mail đến chậm hơn vài chục giây.
3. **XADD OK nhưng DB fail:** retry nhanh 3 lần (200ms → 400ms → 600ms — lỗi DB lúc này thường là transient); vẫn hỏng thì… bỏ tay, chấp nhận để polling đẩy lại gây TRÙNG (consumer tự loại). Tuyệt đối không cố "sửa cho bằng mọi giá" rồi vô tình biến tình huống thừa thành mất.

Nguyên tắc xuyên suốt: listener **không bao giờ ném exception lên request của user** — gửi mail là chuyện nền, fail âm thầm là đúng.

### 5.5 Polling scheduler — `OutboxPollingScheduler`

```java
// common/outbox/scheduler/OutboxPollingScheduler.java
// imports: lombok.RequiredArgsConstructor,
// org.springframework.scheduling.annotation.Scheduled, org.springframework.stereotype.Component,
// java.util.List, + OutboxService/EventStreamProducer/OutboxStreamProperties
/**
 * Đường đẩy DỰ PHÒNG vào Redis Stream: mỗi 10 giây gom một mẻ row PENDING đem XADD.
 * Đây là bean Spring thường (@Component) — @Scheduled chỉ hoạt động trên bean
 * do Spring quản lý (@EnableScheduling đã bật sẵn ở application class).
 */
@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxService outboxService;
    private final EventStreamProducer streamProducer;
    private final OutboxStreamProperties props;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:10000}")  // fixedDelay: đợt xong mới tính 10s kế, không chồng đợt
    public void poll() {
        outboxService.requeueStaleQueued(props.staleQueuedMinutes());        // ① janitor: cứu những row kẹt ở QUEUED

        List<Outbox> batch = outboxService.lockPendingBatch(props.batchSize()); // ② lấy ~100 row về bộ nhớ — khoá DB đã buông ngay lúc SELECT xong
        for (Outbox o : batch) {                                                // ③ vòng for NGOÀI DB transaction
            try {                                                               //    → không giữ connection DB trong lúc gọi Redis
                if (streamProducer.push(o)) {
                    outboxService.markQueued(o.getId());
                }
            } catch (Exception ex) {
                outboxService.registerPushFailure(o.getId(), ex.getMessage());  // ④ ghi nhận hỏng + hẹn giờ thử lại xa dần; hết lượt thì FAILED
            }
        }
    }
}
```

**📖 Giải thích:** v1 bọc `@Transactional` cả vòng for — Redis chậm 5 giây thì connection DB
bị giữ 5 giây × số instance. v2 chỉ giữ connection trong đúng câu SELECT lấy mẻ (~ms). Chạy
2 instance: SKIP LOCKED chia batch cho mỗi bên; lỡ đẩy trùng thì consumer loại (mục 5.8).

Lưu ý: `@EnableScheduling` đã bật sẵn ở `BoilerplateApplication` — không cần cấu hình thêm.

### 5.6 Config Redis — `OutboxStreamConfig`

Đây là **`@Configuration`** — nơi định nghĩa 1 `@Bean` (container) + 1 `@EventListener`:
(1) `@EventListener(ApplicationReadyEvent.class)` tạo consumer group đúng 1 lần lúc start
**rồi start container** (fix NOGROUP — xem giải thích dưới code),
(2) `@Bean StreamMessageListenerContainer` lắng nghe stream và giao message cho `EventStreamConsumer`.
Inject `OutboxStreamProperties props` + `StringRedisTemplate redis` qua constructor.
Tên consumer (`CONSUMER_NAME`) đặt ở đây luôn để reclaimer dùng chung một định nghĩa.
**8 consumer** (`CONSUMER_NAME-w0`..`-w7`) được đăng ký trong cùng group để xử lý song song —
cùng 1 instance `EventStreamConsumer` được dùng chung (xem code dưới).

```java
// common/outbox/config/OutboxStreamConfig.java
// imports: lombok.RequiredArgsConstructor, lombok.extern.slf4j.Slf4j,
// io.lettuce.core.RedisBusyException,
// org.springframework.boot.context.event.ApplicationReadyEvent,
// org.springframework.context.ApplicationContext, org.springframework.context.annotation.{Bean, Configuration},
// org.springframework.context.event.EventListener,
// org.springframework.data.redis.RedisSystemException,
// org.springframework.data.redis.connection.RedisConnectionFactory,
// org.springframework.data.redis.connection.stream.{Consumer, MapRecord, ReadOffset, StreamOffset},
// org.springframework.data.redis.core.StringRedisTemplate,
// org.springframework.data.redis.stream.StreamMessageListenerContainer, java.net.InetAddress, java.time.Duration
/**
 * Cấu hình phía consume: tạo consumer group lúc start app + dựng container lắng nghe stream.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OutboxStreamConfig {

    /** Tên consumer duy nhất per instance (vd "backend-1:m2xk9f"): hostname + hậu tố ngẫu nhiên
     *  sinh đúng 1 lần lúc class load — 2 instance chạy cùng host vẫn khác tên,
     *  nhờ vậy scale ngang không ai giành việc của ai. */
    public static final String CONSUMER_NAME = buildConsumerName();

    private final OutboxStreamProperties props;
    private final StringRedisTemplate redis;
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

    /** Container lắng nghe stream — chế độ MANUAL ACK.
     *  Sử dụng NHIỀU consumer trong cùng group để xử lý song song (thêm 2026-08-29).
     *  - receive(consumer, offset, listener): consumer TỰ quyết định lúc nào ack (mục 5.7),
     *    khác với receiveAutoAck() là ack ngay khi nhận — nguy hiểm: app crash sau ack trước gửi mail = mất mail.
     *  - Serializer mặc định của container options là String → khớp StringRedisTemplate,
     *    KHÔNG cần converter nào (đừng viết `new StringRecordConverter()` — class đó không tồn tại).
     *  - ⚠ KHÔNG start() ở đây — chờ onAppReady() tạo group xong rồi mới start (tránh NOGROUP).
     *
     *  Vì sao nhiều consumer thay vì executor? StreamMessageListenerContainer dù có executor
     *  vẫn poll message tuần tự (đọc 1 message, giao cho executor, chờ xong mới đọc tiếp) —
     *  nên executor không tạo song song thật. Đăng ký nhiều consumer (mỗi consumer 1 tên riêng)
     *  khiến Redis phân phối message round-robin cho từng consumer, mỗi consumer xử lý độc lập
     *  → song song thật sự. */
    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
            RedisConnectionFactory cf, EventStreamConsumer consumer) {

    // Số lượng consumer song song — nên bằng số core hoặc 8 để tối ưu
    int workers = 8;

    var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofMillis(props.pollTimeoutMs()))   // XREADGROUP BLOCK 2000ms — idle thì ngủ, có message dậy trong ≤2s
            .errorHandler(t -> log.error("Stream poll error", t))
            .build();

    var container = StreamMessageListenerContainer.create(cf, options);

    // Đăng ký nhiều consumer với tên khác nhau trong cùng group (CONSUMER_NAME-w0..w7).
    // Mỗi consumer có PEL riêng, Redis chia message cho các consumer → xử lý song song.
    for (int i = 0; i < workers; i++) {
        String workerName = CONSUMER_NAME + "-w" + i;
        container.receive(
            Consumer.from(props.consumerGroup(), workerName),
            StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()), // chỉ đọc message MỚI (cũ hơn do reclaimer lo)
            consumer);
    }
    return container;
}

    /** Chạy SAU KHI app ready (ApplicationReadyEvent): tạo consumer group rồi start container.
     *  - ReadOffset.from("0"): group mới đọc từ ĐẦU stream → không bỏ sót message cũ trước lúc start.
     *  - BUSYGROUP: group đã tồn tại (app restart lần 2+) → nuốt lỗi này, coi như setup xong.
     *  - Vì sao start container ở đây (không phải trong @Bean): container bean được tạo sớm,
     *    nếu start() trong @Bean thì XREADGROUP chạy TRƯỚC khi group tồn tại → lỗi NOGROUP.
     *    @EventListener chạy sau khi mọi bean sẵn sàng → đảm bảo group đã có trước khi consume. */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        try {
            redis.opsForStream().createGroup(
                    props.streamKey(), ReadOffset.from("0"), props.consumerGroup());
            log.info("Consumer group '{}' created on stream '{}'", props.consumerGroup(), props.streamKey());
        } catch (RedisSystemException ex) {
            // BUSYGROUP bị wrap trong RedisSystemException → check root cause
            if (ex.getCause() instanceof RedisBusyException) {
                log.info("Consumer group '{}' already exists", props.consumerGroup());
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
```

> [!important] Nguồn chính thức
> Spring Data Redis reference (*Redis Streams*): `receive(consumer, offset, listener)` =
> manual ack — consumer tự ACK mọi đường kể cả lỗi; `receiveAutoAck(...)` mới là auto.
> v1 từng dùng `@EventListener(MapRecord)` phụ thuộc cấu hình publish application events,
> khó kiểm soát ack → bỏ.

**📖 Giải thích khái niệm — đọc trước khi nhìn code:**

- **Consumer group** = một tổ nhân viên cùng đọc một cái bảng tin. Mỗi message chỉ đúng **1 người** trong tổ nhận → chạy 3 instance backend = 3 nhân viên chia nhau việc, scale ngang "miễn phí".
- **Nhiều consumer trong cùng instance (thêm 2026-08-29):** thay vì chỉ 1 consumer, ta đăng ký
  **8 consumer** (`CONSUMER_NAME-w0`..`-w7`) trong cùng group. Redis phân phối message
  round-robin cho 8 consumer → 8 email xử lý song song. Lưu ý: `StreamMessageListenerContainer`
  dù có executor vẫn poll tuần tự, nên **executor không tạo song song thật** — phải dùng nhiều
  consumer. Kết quả thực tế: 8 email đầu xử lý trong ~5 giây (trước đây single-thread ~40 giây).
- Lần đầu tạo group: `ReadOffset.from("0")` = đọc bảng tin **từ dòng đầu tiên** (nhặt cả tin cũ tồn từ trước lúc deploy). Các lần start sau gặp lỗi `BUSYGROUP` — không phải lỗi thật, chỉ là "tổ này lập rồi".
- Khi nhận việc hằng ngày: `ReadOffset.lastConsumed()` = chỉ lấy **tin mới**. Tin cũ bị bỏ dở vì crash là việc của reclaimer (5.8), không phải người mới vào ca.
- `pollTimeout(2s)` → lệnh `XREADGROUP BLOCK 2000`: không có tin thì ngủ 2 giây thay vì hỏi xoành xoạch — nhàn cho Redis, có tin thì dậy trong ≤2s.
- **Manual ACK** = "làm xong mới ký nhận". Auto-ACK (ký ngay khi bưng việc về) nghe tiện nhưng app chết ngay sau đó = việc bay màu — đây là lựa chọn sống còn của toàn bộ thiết kế.
- **Start container trong `onAppReady()` (fix NOGROUP, thêm 2026-08-29):** container bean được
  tạo sớm (`@Bean`). Nếu `container.start()` chạy ngay trong `@Bean`, lệnh `XREADGROUP` sẽ gọi
  **trước khi** consumer group tồn tại → lỗi `NOGROUP`. Vì vậy `@Bean` chỉ dựng container
  (không start), còn `onAppReady()` (chạy sau khi mọi bean sẵn sàng) tạo group rồi mới
  `applicationContext.getBean(...).start()`. Lỗi `BUSYGROUP` (group đã tồn tại khi restart)
  được nhận diện qua `ex.getCause() instanceof RedisBusyException` (Lettuce wrap trong
  `RedisSystemException`).

### 5.7 Consumer — `EventStreamConsumer implements StreamListener`

```java
// common/outbox/consumer/EventStreamConsumer.java
// imports: lombok.RequiredArgsConstructor, lombok.extern.slf4j.Slf4j,
// org.springframework.data.redis.connection.stream.MapRecord,
// org.springframework.data.redis.core.StringRedisTemplate,
// org.springframework.data.redis.stream.StreamListener, org.springframework.stereotype.Component,
// + OutboxRepository/OutboxService/EventHandlerRegistry/OutboxStatus
/**
 * Consumer chính: nhận message từ Redis Stream (container ở OutboxStreamConfig giao từng
 * message tới đây), gọi handler theo eventType, rồi TỰ quyết lúc ACK (manual ack).
 * implements StreamListener<String, MapRecord<...>> — điều kiện để container gọi được onMessage;
 * bản thân nó là @Component để inject dependencies + được nhúng vào container bean ở mục 5.6.
 *
 * ⚠ Thread-safe (thêm 2026-08-29): với 8 consumer (mục 5.6), cùng 1 instance @Component này
 * được gọi SONG SONG từ nhiều thread. Phải đảm bảo: không giữ state mutable, mỗi message
 * xử lý độc lập, không chia sẻ biến giữa các lần gọi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final OutboxRepository outboxRepository;
    private final OutboxService outboxService;
    private final EventHandlerRegistry handlerRegistry;
    private final StringRedisTemplate redis;
    private final OutboxStreamProperties props;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
    long outboxId = Long.parseLong(record.getValue().get("outboxId"));
    var opt = outboxRepository.findById(outboxId);

    // ── CHỐNG TRÙNG (nhận trùng message cũng không sao) ────────────────────
    // Cùng 1 sự kiện có thể nằm trong stream 2 lần (listener + polling cùng đẩy).
    // Bản sau tới lượt sẽ thấy row đã SENT → chỉ việc ACK nhặt xác, KHÔNG gửi mail nữa.
    // Logic này chỉ đúng nhờ quy tắc: SENT do CONSUMER đặt SAU KHI gửi OK (bug #1 của v1).
    if (opt.isEmpty() || opt.get().getStatus() == OutboxStatus.SENT) {
        acknowledge(record);
        return;
    }

    try {
        // dispatch theo eventType → EmailHandler/WebhookHandler...
        handlerRegistry.getByEventType(record.getValue().get("eventType"))
                       .handle(record.getValue().get("payload"));

        outboxService.markSent(outboxId);      // DB báo "đã xử lý"
        acknowledge(record);                   // rồi mới ACK Redis — THỨ TỰ này quan trọng:
                                               // crash giữa 2 dòng trên → redelivery thấy SENT → chỉ ack, không gửi lại
    } catch (Exception ex) {
        log.error("Handle fail outbox={} — chờ XAUTOCLAIM", outboxId, ex);
        outboxService.noteProcessingError(outboxId, ex.getMessage()); // chỉ ghi last_error
        // KHÔNG ack → message nằm lại PEL, PendingReclaimer (5.9) sẽ lo
    }
}

private void acknowledge(MapRecord<String, String, String> record) {
    redis.opsForStream().acknowledge(                 // redis = StringRedisTemplate đã inject
            props.streamKey(), props.consumerGroup(), record.getId());
}
}
```

> [!note] Cửa sổ trùng còn sót lại (rất hẹp)
> Crash đúng khoảnh khắc "handler gửi mail OK nhưng chưa kịp markSent" → redelivery sẽ gửi
> lại 1 lần nữa. Cửa sổ tương tự tồn tại khi 2 consumer instance đọc 2 bản copy gần đồng thời
> (bản gốc + bản polling đẩy lại) trong lúc bản đầu vẫn đang xử lý chậm. Hậu quả = 1 mail nhân
> đôi — chấp nhận được theo at-least-once. Muốn triệt tiêu hẳn, chọn 1 trong 2 (optional,
> giai đoạn 2):
>
> - **Guard Redis:** đầu handler `SET outbox:sent:{id} NX EX 3600` — thằng nào SET được mới
>   gửi. Rẻ, nhưng TTL 1h nghĩa là replay muộn hơn 1h vẫn trùng.
> - **Atomic claim DB (khuyến nghị):** trước khi handle,
>   `UPDATE outbox SET status='PROCESSING', locked_by=?, locked_at=now() WHERE id=? AND status IN ('PENDING','QUEUED')`
>   — chỉ `affected == 1` mới được handle; crash để lại PROCESSING thì reclaimer hồi nó về
>   PENDING sau timeout. Chặt chẽ hơn, đổi lại thêm 1 trạng thái.

**📖 Giải thích `onMessage` — đi theo 3 lối rẽ:**

- **Lối vui:** row chưa `SENT` → dispatch handler → render + gửi SMTP → `markSent` → `acknowledge`. Hai bước cuối **bắt buộc đúng thứ tự**: nếu ACK trước mà app chết ngay sau đó, Redis tưởng đã giao xong → message mất trắng, mail không bao giờ được gửi lại.
- **Lối trùng:** bản copy thứ 2 tới nơi, thấy row đã `SENT` → chỉ ACK rồi đi. Như nhận lại hoá đơn đã thanh toán: gấp gọn bỏ túi, không rút ví lần nữa.
- **Lối lỗi:** SMTP ném exception → **KHÔNG ack** → message ở lại PEL (việc đã bưng ra nhưng chưa ký nhận), reclaimer 60 giây sau ghé nhặt. `noteProcessingError` chỉ ghi chú `last_error` để debug — bộ đếm retry chính thức là `deliveryCount` của Redis, tự tăng mỗi lần redelivery.

### 5.8 PendingReclaimer — mỗi 30 giây

Khái niệm: khi consumer nhận message mà chưa ack, message nằm trong **PEL** (Pending Entries
List) của consumer đó. Nếu consumer crash, message **không tự quay lại** — `XREADGROUP >`
chỉ đọc message mới. Phải có ai đó "nhặt lại":

1. `XPENDING findjob:event-queue <group> IDLE 60000 - + 50` — liệt kê message chưa ack mà
   **im lặng quá 60 giây** (đang xử lý dưới 60s thì thôi kẹt, tránh cướp việc của consumer
   đang sống).
2. Với mỗi entry, xem `deliveryCount` (đã được giao bao nhiêu lần):
   - `< max_retries` → `XAUTOCLAIM ... 60000 <messageId>` — chuyển message sang consumer
     mình, nó sẽ được xử lý lại. Khoảng idle 60s này chính là **nhịp retry tối thiểu** phía
     consume.
   - `>= max_retries` → message hỏng thật: `XADD findjob:event-dlq * <fields>` (lưu nguyên
     vẹn để debug/requeue tay) + `XACK` (nhặt xác khỏi group) + `markFailed(id, "exceeded deliveries")` trong DB.

> [!warning] `XACK` không xoá entry khỏi stream
> ACK chỉ gạch tên khỏi PEL. Entry vật lý còn nằm trong stream tới khi bị trim (MAXLEN) hoặc
> `XDEL`. DLQ là **stream khác**, không phải "xoá khỏi stream chính".

Code hoàn chỉnh:

```java
// common/outbox/reclaimer/PendingReclaimer.java
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingReclaimer {

    private static final String CONSUMER_NAME = OutboxStreamConfig.CONSUMER_NAME; // duy nhất per instance — định nghĩa ở mục 5.6
    // ⚠ KHÔNG có hậu tố -wN: reclaimer là consumer RIÊNG, tách khỏi 8 worker (w0..w7) ở mục 5.6.
    //    XCLAIM tự tạo consumer này nếu chưa tồn tại trong group → không cần đăng ký trước.

    private final StringRedisTemplate redis;
    private final OutboxRepository outboxRepository;
    private final OutboxStreamProperties props;
    private final EventStreamConsumer eventStreamConsumer;       // để xử lý lại tin claim được

    @Scheduled(fixedDelayString = "${app.outbox.reclaim-interval-ms:30000}")
    public void reclaim() {
        var ops = redis.opsForStream();
        String stream = props.streamKey();
        String group  = props.consumerGroup();

        // ① XPENDING — liệt kê message đã giao mà chưa ack.
        //    Spring API chưa có tham số IDLE trực tiếp → tự lọc bằng getElapsedTimeSinceLastDelivery().
        PendingMessages pendings = ops.pending(stream, group, Range.unbounded(), 50);
        if (pendings == null) return;

        for (PendingMessage pm : pendings) {
            if (pm.getElapsedTimeSinceLastDelivery().toMillis() < props.reclaimIdleMs())
                continue;   // consumer sống đang xử lý — không cướp việc

            long deliveryCount = pm.getTotalDeliveryCount();

            // Claim TRƯỚC để đọc fields — PEL chỉ cho biết entry-id của Redis,
            // KHÔNG chứa payload nên chưa biết outboxId là bao nhiêu.
            // ⚠ opsForStream() trả StreamOperations<String, Object, Object> → claim() trả
            //    List<MapRecord<String, Object, Object>>, KHÔNG phải <String, String, String>.
            List<MapRecord<String, Object, Object>> claimed = ops.claim(
                    stream, group, CONSUMER_NAME,
                    Duration.ofMillis(props.reclaimIdleMs()), pm.getId());
            if (claimed.isEmpty()) continue;
            MapRecord<String, Object, Object> raw = claimed.getFirst();

            // Convert về <String, String, String> — runtime vốn đã là String (StringRedisTemplate)
            // nên chỉ đổi khai báo kiểu. mapEntries() giữ stream key, withId() giữ record id →
            // acknowledge(record) trong onMessage vẫn ACK đúng entry.
            MapRecord<String, String, String> rec = raw
                    .mapEntries(entry -> Map.entry(
                            String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue())))
                    .withId(raw.getId());

            long outboxId = Long.parseLong(rec.getValue().get("outboxId"));
            int maxRetries = outboxRepository.findById(outboxId)
                    .map(Outbox::getMaxRetries).orElse(5);   // max_retries từng row (cho override theo event)

            if (deliveryCount < maxRetries) {
                // ② Còn lượt → xử lý lại NGAY TẠI ĐÂY.
                // ⚠ Container chỉ tự nhận tin MỚI (XREADGROUP ">") — tin vừa claim nằm trong PEL
                //     nên PHẢI tự gọi onMessage, không chờ container giao lại!
                eventStreamConsumer.onMessage(rec);
                log.info("Reclaimed outbox {} (delivery #{})", outboxId, deliveryCount);
            } else {
                // ③ Hết lượt → XADD nguyên bản sang DLQ (giữ fields để debug/requeue tay)
                //     → XACK nhặt xác khỏi group chính → row FAILED trong DB.
                ops.add(StreamRecords.string(rec.getValue())
                        .withStreamKey(props.dlqStreamKey()),
                        RedisStreamCommands.XAddOptions.maxlen(10000).approximateTrimming(true));
                ops.acknowledge(stream, group, pm.getId());
                outboxRepository.markFailed(outboxId,
                        "Exceeded max deliveries (" + deliveryCount + ")");
                log.error("Outbox {} → DLQ sau {} lần giao", outboxId, deliveryCount);
            }
        }
    }
}
```

**📖 Giải thích 2 điểm dễ sai:**

- **Tin claim không tự chạy:** container dùng `XREADGROUP >` chỉ nhận tin *chưa ai giao* — tin vừa XAUTOCLAIM nằm im trong PEL của mình. Nên bước ② gọi thẳng `onMessage()` thay vì chờ container; nhờ vậy cũng kiểm soát được thứ tự markSent → ACK như đường thường.
- **Đọc fields trước khi quyết định:** `deliveryCount` lấy từ `XPENDING` (trước claim), còn `outboxId`/payload phải claim xong mới đọc được — đó là lý do claim đứng trước cả hai nhánh.
- **Kiểu trả về của `claim()` là `<String, Object, Object>`:** `opsForStream()` (không tham số) trả `StreamOperations<String, Object, Object>`, nên khai báo `List<MapRecord<String, String, String>> = ops.claim(...)` sẽ **không compile**. Runtime giá trị vốn là String (StringRedisTemplate) → chỉ cần convert kiểu như đoạn code trên bằng `mapEntries(...)` + `withId(...)`.

### 5.9 Handler registry + EmailHandler

Registry: map `eventType → EventHandler`, đăng ký bằng constructor injection (dễ mock khi
test, không lệ thuộc vòng đời `@PostConstruct` như v1). Payload thô ví dụ:

```json
{
  "to": "nam@example.com",
  "templateName": "email/otp",
  "variables": { "username": "nam", "otp": "482913" }
}
```

Dispatch sang các method **đã có sẵn** của `EmailService` — không viết mới logic mail:

| eventType | Method hiện hữu |
|---|---|
| `EMAIL_OTP` | `sendOtpEmail(to, username, otp)` |
| `EMAIL_WELCOME` | `sendWelcomeEmail(to, username)` |
| `EMAIL_APPLICATION_ACCEPTED` | `sendApplicationAcceptedEmail(to, fullName, jobTitle, companyName)` |
| `EMAIL_APPLICATION_REJECTED` | `sendApplicationRejectedEmail(to, fullName, jobTitle, companyName, rejectedReason)` |
| `EMAIL_GENERIC` | `sendHtmlEmail(to, subject, htmlContent)` |

**📖 Giải thích luồng dữ liệu trong handler:** consumer đưa nguyên xi chuỗi JSON (`payload` của entry) cho handler → parse ra `to`, `templateName`, `variables` → render HTML bằng Thymeleaf (vd template `email/otp` + biến `username`, `otp`) → gọi đúng method `EmailService` theo bảng dưới → SMTP đi. Registry chỉ là **cuốn danh bạ** eventType → người chịu trách nhiệm: thêm loại email mới = thêm 1 dòng đăng ký, consumer không phải sửa dòng nào.

Interface + Registry + Handler:

```java
// common/outbox/handler/EventHandler.java
public interface EventHandler {

    /** Các eventType handler này nhận — registry dựa vào đây tự lập danh bạ lúc khởi tạo. */
    Set<String> supportedTypes();

    void handle(String payloadJson) throws Exception;
}
```

```java
// common/outbox/handler/EventHandlerRegistry.java
@Component
public class EventHandlerRegistry {

    private final Map<String, EventHandler> byEventType = new ConcurrentHashMap<>();

    /** Constructor injection: Spring nhét VỌT mọi EventHandler có mặt trong context,
     *  registry tự đăng ký — không @PostConstruct (v1 từng quên đăng ký vì lifecycle lệch). */
    public EventHandlerRegistry(List<EventHandler> allHandlers) {
        allHandlers.forEach(h -> h.supportedTypes()
                .forEach(type -> byEventType.put(type, h)));
    }

    public EventHandler getByEventType(String eventType) {
        EventHandler h = byEventType.get(eventType);
        if (h == null) throw new IllegalStateException("No handler for event type " + eventType);
        return h;   // quăng ở đây → onMessage bắt → không ack → reclaimer/DLQ lo
    }
}
```

```java
// features/email/handler/EmailHandler.java
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailHandler implements EventHandler {

    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    private static final Set<String> TYPES = Set.of(
            "EMAIL_OTP", "EMAIL_WELCOME",
            "EMAIL_APPLICATION_ACCEPTED", "EMAIL_APPLICATION_REJECTED", "EMAIL_GENERIC");

    @Override public Set<String> supportedTypes() { return TYPES; }

    @Override
    public void handle(String payloadJson) throws Exception {
        var root = objectMapper.readTree(payloadJson);   // payload JSON nguyên xi từ DB
        String to   = root.path("to").asText();
        var vars    = root.path("variables");

        switch (root.path("templateName").asText()) {   // dispatch theo template — thêm mail mới chỉ thêm case
            case "email/otp" -> emailService.sendOtpEmail(to,
                    vars.path("username").asText(), vars.path("otp").asText());
            case "email/welcome" -> emailService.sendWelcomeEmail(to,
                    vars.path("username").asText());
            case "email/application-accepted" -> emailService.sendApplicationAcceptedEmail(to,
                    vars.path("fullName").asText(), vars.path("jobTitle").asText(),
                    vars.path("companyName").asText());
            case "email/application-rejected" -> emailService.sendApplicationRejectedEmail(to,
                    vars.path("fullName").asText(), vars.path("jobTitle").asText(),
                    vars.path("companyName").asText(), vars.path("rejectedReason").asText());
            default -> emailService.sendHtmlEmail(to,          // EMAIL_GENERIC — tuỳ biến hoàn toàn qua payload
                    root.path("subject").asText(), root.path("htmlContent").asText());
        }
    }
}
```

> [!warning] `@Async` trong EmailService — ĐÃ BỎ (2026-08-28)
> `@Async` đã được gỡ khỏi 4 method template (`sendOtpEmail`, `sendWelcomeEmail`,
> `sendApplicationAcceptedEmail`, `sendApplicationRejectedEmail`). Đây là bước bắt buộc để
> consumer gửi mail sync — exception lan truyền → không ACK → retry/DLQ hoạt động đúng.
>
> ⚠️ **Phân biệt với `@Async` của listener (mục 5.4):** `@Async("emailTaskExecutor")` trên
> `OutboxEventListener.onSaved` là **đúng và cần thiết** — listener chỉ đẩy event vào Redis +
> markQueued (không gọi Java Mail), và có fallback polling nên an toàn. Còn `@Async` trên
> `EmailService` (gửi mail trực tiếp) là **sai** — không có fallback, crash = mất mail, và
> Java Mail dùng `synchronized` → bị Virtual Thread pinning. Hai chỗ dùng `@Async` hoàn toàn
> khác nhau, đừng nhầm.
>
> ⚠️ **HỆ QUẢ TẠM THỜI:** vì `@Async` bị bỏ TRƯỚC khi migrate 6 call site sang outbox,
> các API `register`/`resendOtp`/`verifyOtp`/duyệt-từ-chối-hồ-sơ hiện đang **chặn chờ SMTP
> 1–3s**. Phải migrate 6 call site (mục 8, bước 11–12) NGAY để khôi phục tốc độ API —
> sau khi migrate, SMTP chỉ chạy trên thread consumer nền, API không còn chờ.
>
> Kèm theo: SMTP timeout trong `application.yml` (JavaMail mặc định chờ vô hạn —
> 1 connection treo sẽ treo vĩnh viễn thread consumer):
> ```yaml
> spring:
>   mail:
>     properties:
>       mail:
>         smtp:
>           connectiontimeout: 5000
>           timeout: 10000
>           writetimeout: 10000
> ```

---

## 6. Phân tích failure mode — vì sao thiết kế này "không mất"

| # | Sự cố | Hậu quả | Vì sao an toàn |
|---|---|---|---|
| 1 | Commit xong, app crash trước khi listener kịp chạy | Row nằm `PENDING` | Polling ≤10s sau nhặt lên đẩy bình thường. Không mất. |
| 2 | Redis chết lúc fast-path push | Giữ `PENDING`, log warn | Polling đẩy khi Redis sống lại. Chậm hơn, không mất. API user không lỗi. |
| 3 | **XADD OK nhưng `markQueued` fail** (DB timeout, pool cạn…) | Stream có event, row vẫn `PENDING` | Polling 10s sau **đẩy lại lần nữa** → stream có 2 bản copy. Consumer xử lý bản 1, đặt `SENT`; bản 2 tới thấy `SENT` → chỉ ACK. Kết quả: **trùng trong hàng, không trùng mail, không mất mail**. Đây là lý do `QUEUED` chỉ là hint, không phải nguồn chân truth. |
| 4 | Nếu đảo thứ tự: mark `QUEUED` trước rồi mới push | Crash giữa 2 bước → row `QUEUED` mà stream trống rỗng | Phải đợi janitor 15 phút mới cứu — đó là lý do v2 chọn **push-trước-mark**: fail hướng nào cũng rơi vào phía "thừa" chứ không "thiếu". |
| 5 | 2 bản copy được 2 instance consume gần đồng thời, bản 1 xử lý chậm (>10s) | Có thể gửi đôi | Cửa sổ rất hẹp; muốn triệt tiêu → atomic claim / SETNX guard (5.7). |
| 6 | Consumer crash sau khi gửi mail, trước khi `markSent`/`XACK` | Redelivery | Nhờ thứ tự `markSent → XACK`, lần giao lại thấy `SENT` → chỉ ACK. Không gửi đôi. |
| 7 | Redis flush / MAXLEN trim cắt entry chưa consume | Row kẹt `QUEUED` mãi | Janitor 15 phút đưa về `PENDING` → đẩy lại. Có thể trùng — vô hại. |
| 8 | SMTP chết dai dẳng | Message retry mãi? | `deliveryCount >= max_retries` → DLQ + row `FAILED`. Người vận hành soi DLQ requeue tay. |

Tóm lại: mọi failure đều hội tụ về một trong hai trạng thái an toàn — **thừa (dedupe bởi
`SENT`) hoặc chậm (retry bởi polling/reclaimer/janitor)**. Không có đường nào dẫn đến mất.

---

## 7. Cấu hình — `application.yml` + `OutboxStreamProperties`

```yaml
app:
  outbox:
    stream-key: ${OUTBOX_STREAM_KEY:findjob:event-queue}
    dlq-stream-key: ${OUTBOX_DLQ_KEY:findjob:event-dlq}
    consumer-group: ${OUTBOX_GROUP:findjob-workers}
    poll-interval-ms: ${OUTBOX_POLL_MS:10000}
    batch-size: ${OUTBOX_BATCH:100}
    maxlen: ${OUTBOX_MAXLEN:50000}
    stale-queued-minutes: ${OUTBOX_STALE_QUEUED_MIN:15}
    reclaim-interval-ms: ${OUTBOX_RECLAIM_MS:30000}
    reclaim-idle-ms: ${OUTBOX_RECLAIM_IDLE_MS:60000}
    poll-timeout-ms: ${OUTBOX_POLL_TIMEOUT_MS:2000}   # BLOCK của XREADGROUP trong container
```

Bind bằng `@ConfigurationProperties(prefix = "app.outbox")` record — inject vào producer,
scheduler, config, reclaimer. Không dùng hằng số rải rác.

> [!note] Phụ thuộc `AsyncConfig` (thêm 2026-08-29)
> `OutboxEventListener.onSaved` dùng `@Async("emailTaskExecutor")` (mục 5.4) — executor này
> được định nghĩa trong `AsyncConfig` (`@Bean(name = "emailTaskExecutor")` trả về
> `Executors.newVirtualThreadPerTaskExecutor()`). Đã có sẵn trong project, không cần thêm
> config mới. Lưu ý: executor này dùng Virtual Thread — phù hợp cho listener (I/O nhẹ, không
> gọi Java Mail), nhưng **không** dùng cho `EmailService` (Java Mail dùng `synchronized` →
> Virtual Thread pinning, xem mục 5.9).

> [!note] Số consumer song song (thêm 2026-08-29)
> Số worker (`int workers = 8`) được **hardcode** trong `OutboxStreamConfig.container()`
> (mục 5.6), **không** phải config property. Lý do: số consumer phụ thuộc phần cứng (số core)
> hơn là cấu hình triển khai, và hardcode giúp tránh lệch config giữa các môi trường. Muốn
> đổi số worker → sửa hằng số `workers` trong `OutboxStreamConfig`.

**📖 Giải thích từng key:**

| Key | Ý nghĩa | Mặc định |
|---|---|---|
| `stream-key` / `dlq-stream-key` | Tên băng chuyền chính / sọt "hoá đơn chết" | `findjob:event-queue` / `findjob:event-dlq` |
| `consumer-group` | Tên tổ consumer | `findjob-workers` |
| `poll-interval-ms` | Nhịp polling đường chậm | 10s |
| `batch-size` | Số row claim mỗi vòng polling | 100 |
| `maxlen` | Trần entry trong stream (trim xấp xỉ) | 50 000 |
| `stale-queued-minutes` | Ngưỡng janitor cứu row kẹt `QUEUED` | 15 phút |
| `reclaim-interval-ms` / `reclaim-idle-ms` | Nhịp reclaimer / message treo bao lâu thì claim lại | 30s / 60s |
| `poll-timeout-ms` | Thời gian container ngủ khi stream trống (BLOCK của XREADGROUP) | 2000ms |

Record bind:

```java
// common/outbox/config/OutboxStreamProperties.java
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxStreamProperties(
        String streamKey,
        String dlqStreamKey,
        String consumerGroup,
        long pollIntervalMs,
        long pollTimeoutMs,
        int batchSize,
        long maxlen,
        int staleQueuedMinutes,
        long reclaimIntervalMs,
        long reclaimIdleMs
) {}
```

Bật binding bằng `@ConfigurationPropertiesScan` trên application class (kiểm tra — nếu đã có thì thôi). Kebab-case trong yaml tự map sang camelCase record component.

Pattern `${ENV_VAR:default}` khớp style hiện có của `application.yml` — mọi con số đều chỉnh qua biến môi trường, không cần build lại.

**Bổ sung so với plan gốc — 2 config bắt buộc khi consumer gửi mail sync:**

1. **SMTP timeout** (JavaMail mặc định chờ vô hạn — 1 connection treo sẽ treo vĩnh viễn
   thread consumer, mọi mail sau chết đói):
   ```yaml
   spring:
     mail:
       properties:
         mail:
           smtp:
             connectiontimeout: 5000
             timeout: 10000
             writetimeout: 10000
   ```
2. **Scheduling pool size** (Spring `@Scheduled` mặc định 1 thread — `pollOutbox` và
   `reclaim` sẽ chặn nhau; reclaimer còn gọi SMTP sync nên cần tách thread):
   ```yaml
   spring:
     task:
       scheduling:
         pool:
           size: 4
   ```

---

## 8. Kế hoạch triển khai từng bước

Giai đoạn 1 — xương sống (một PR): ✅ ĐÃ HOÀN TẤT

1. [x] Flyway `V16__create_outbox_table.sql`
2. [x] Entity `Outbox` + enum `OutboxStatus` + `OutboxRepository` (query ở mục 4.2)
3. [x] `OutboxService`: `savePending`, `markQueued`, `markSent`, `noteProcessingError`,
       `registerPushFailure`, `lockPendingBatch`, `requeueStaleQueued` *(lưu ý: `markFailed`
       KHÔNG nằm ở service — reclaimer gọi `outboxRepository.markFailed` trực tiếp)*
4. [x] `EventStreamProducer` + record `OutboxSavedEvent` + `OutboxEventListener` (mục 5.3–5.4)
5. [x] `OutboxPollingScheduler` + janitor (mục 5.5)
6. [x] `OutboxStreamConfig`: tạo group + container manual-ACK (mục 5.6)
7. [x] `EventHandlerRegistry` + `EventStreamConsumer` (mục 5.7)
8. [x] `EmailHandler` map 5 eventType sang `EmailService` (mục 5.9)
9. [x] `PendingReclaimer` + DLQ (mục 5.8) — **bổ sung so với plan:** DLQ push kèm
       `XAddOptions.maxlen(10000)` để DLQ không phình vô hạn
10. [x] `application.yml` + `OutboxStreamProperties` (mục 7) — **bổ sung so với plan:**
       SMTP timeout (5s/10s/10s) + `spring.task.scheduling.pool.size: 4` (vì reclaimer
       gọi SMTP sync, cần tách thread khỏi poller)

Giai đoạn 2 — tích hợp nghiệp vụ (tách PR riêng theo domain):

11. [ ] `AuthService.register`: thay gọi thẳng `sendOtpEmail`/`sendWelcomeEmail` bằng
      `savePending + publishEvent` trong cùng TX
12. [ ] `ApplicationService` (duyệt/từ chối hồ sơ): chuyển sang
      `EMAIL_APPLICATION_ACCEPTED`/`REJECTED`
13. [ ] Dọn chỗ cũ: các chỗ gọi `EmailService` trực tiếp ngoài outbox; **bỏ `@Async`**
      khỏi 4 method template (xem cảnh báo mục 5.9)

Giai đoạn 3 — vận hành:

14. [ ] Metrics/log: đếm `XLEN`, số row `PENDING`, kích thước PEL; cảnh báo khi vượt ngưỡng
15. [ ] Tool xem DLQ + script requeue tay
16. [ ] Guard chống trùng (atomic claim hoặc SETNX) nếu số liệu cho thấy cần

---

## 9. Kế hoạch test

**Unit test (không cần Redis/DB thật):**

- `savePending` serialize payload lỗi → quăng exception (TX phải rollback).
- Listener: push fail → không mark gì, không quăng ra ngoài; push OK + mark fail 3 lần →
  log error, không quăng.
- **Listener chạy trên thread riêng (`@Async`):** sau commit, `markQueued` phải mở TX mới
  thành công (không `TransactionRequiredException`) — verify listener chạy trên thread khác
  request thread (mục 5.4).
- Consumer: row `SENT` → ACK mà không gọi handler; handler ném lỗi → không ACK,
  `noteProcessingError` được gọi.
- Repository query `registerPushFailure`: đúng nhánh backoff vs `FAILED` khi hết lượt.

**Integration test (Testcontainers: PostgreSQL + Redis):**

- Luồng vui: register → row PENDING → XADD → consumer gửi → `SENT` + XACK.
- Redis chết lúc push (dừng container) → response API vẫn 200; start lại Redis → polling
  đẩy thành công ≤ poll-interval + buffer.
- Nhồi 2 bản copy cùng outboxId → mail gửi đúng 1 lần.
- Consumer crash giả lập (không ack) → XAUTOCLAIM nhặt lại sau idle 60s.
- `deliveryCount` vượt ngưỡng → entry nằm trong DLQ + row `FAILED`.
- Publish event NGOÀI transaction → listener không chạy (document cái bẫy ở 5.1).
- **8 consumer song song (thêm 2026-08-29):** nhồi 8+ email → verify chúng được xử lý
  song song (thời gian < tổng thời gian tuần tự), không có email nào bị bỏ sót, không
  duplicate mail (mỗi outboxId chỉ gửi 1 lần dù 8 consumer cùng chạy).

---

## 10. Rủi ro & giảm thiểu

| Rủi ro | Giảm thiểu |
|--------|-----------|
| Redis unavailable kéo dài | Outbox tích trong DB, polling retry + backoff; alert khi `PENDING` tăng bất thường |
| Mail gửi đôi (cửa sổ hẹp mục 6.5) | Chấp nhận at-least-once; nâng cấp atomic claim nếu cần |
| Trim cắt entry chưa consume | Janitor `QUEUED → PENDING` sau 15 phút |
| Polling nhiều instance giành row | `FOR UPDATE SKIP LOCKED`, TX ngắn |
| Connection DB bị giữ lâu | Không bọc call Redis trong transaction (mục 5.5) |
| Quên publish event trong TX (5.1) | Integration test bắt buộc + code review checklist |
| Listener `@Async` mất event nếu app crash giữa push & markQueued | Row giữ PENDING → polling đẩy lại (mục 5.4) — không mất, chỉ chậm |
| 8 consumer song song gây race (thêm 2026-08-29) | Cùng 1 outboxId có thể bị 2 consumer xử lý cùng lúc → cả 2 gửi mail. Giảm thiểu: `EventStreamConsumer` thread-safe (không state mutable), check `SENT` trước khi gửi + `markSent` sau khi gửi OK; nếu vẫn lo ngại → nâng cấp atomic claim (mục 16) |

---

## 11. Kết luận

Kiến trúc **Outbox 4 trạng thái + dual-path push + manual-ACK consumer + reclaimer + janitor**
đạt mục tiêu "không mất" với chi phí thấp, chấp nhận trùng có kiểm soát và xử lý trùng ở
consumer bằng quy tắc đơn giản: `SENT` do consumer đặt sau khi gửi OK. Toàn bộ thành phần đều
có thể test độc lập và rollout theo 3 giai đoạn không downtime.

---

## Phụ lục A — Bảng thuật ngữ nhanh

| Thuật ngữ | Nghĩa đời thường |
|---|---|
| **Transactional Outbox** | Ghi "việc cần làm" vào 1 bảng DB **cùng transaction** với nghiệp vụ — commit cùng sống, rollback cùng chết. Bảo đảm "có nghiệp vụ thì ắt có việc". |
| **Redis Stream** | Băng chuyền message của Redis; mỗi entry có ID tự tăng, nằm yên trên băng chuyền tới khi bị trim. |
| **XADD / XREADGROUP / XPENDING / XAUTOCLAIM / XACK** | Đưa việc lên băng chuyền / nhận việc mới theo nhóm / liệt kê việc đã nhận chưa ký / cướp việc kẹt của đồng nghiệp / ký nhận đã làm xong. |
| **Consumer group** | Tổ nhân viên đọc chung một bảng tin; mỗi message đúng 1 người nhận. |
| **PEL** (Pending Entries List) | Sổ "đã giao tay nhưng chưa ký nhận" của từng consumer — nguồn để reclaimer soi. |
| **Manual ACK** | Làm xong mới ký nhận. Ký sớm mà chết giữa chừng = mất việc. |
| **DLQ** (Dead Letter Queue) | Sọt "hoá đơn chết": message thử quá số lần vẫn fail, chờ người xử lý tay (debug/requeue). |
| **Idempotent** | Làm 10 lần cũng như làm 1 lần — thuộc tính bắt buộc của handler vì delivery là at-least-once. |
| **At-least-once / Exactly-once** | Ít nhất 1 lần (có thể thừa) / đúng 1 lần (rất đắt — không theo). Thiết kế này chọn at-least-once + dedupe ở consumer. |
| **Backoff** | Retry càng lúc càng xa nhau (30s → 1p → 2p…) thay vì dồn dập giày đạp hệ thống đang lỗi. |
| **Fast path / Polling** | Đường nhanh sau commit (XADD trong vài ms) và đường chậm dự phòng (gom mỗi 10s) — fail đường nào thì đường kia lót. |
| **Janitor / Reclaimer** | Hai ông bảo vệ: janitor rà row DB kẹt `QUEUED`, reclaimer rà message Redis kẹt PEL — mỗi ông một khu, không đụng việc nhau. |
