# Kế hoạch chuyển đổi sang UUID v7 cho khóa chính

**Ngày:** 2026-08-24  
**Dự án:** FINDJOB-BE  
**Tác giả:** AI Assistant

---

## 1. Mục tiêu

- Chuyển đổi tất cả các khóa chính (ID) trong hệ thống từ kiểu `Long` tự tăng sang **UUID v7**.
- Đảm bảo **keyset pagination** vẫn hoạt động hiệu quả (vì UUID v7 có thứ tự theo thời gian).
- Tăng cường bảo mật và khả năng phân tán.
- Giảm phụ thuộc vào database sequence.
- Hỗ trợ merge dữ liệu từ nhiều nguồn mà không xung đột ID.

---

## 2. Lý do chọn UUID v7 thay vì UUID v4

| Tiêu chí | UUID v4 (random) | UUID v7 (time-ordered) |
|----------|------------------|-------------------------|
| Tính duy nhất | ✅ | ✅ |
| Bảo mật | ✅ (khó đoán) | ✅ (khó đoán) |
| Keyset pagination | ❌ (không có thứ tự) | ✅ (có thứ tự thời gian) |
| Hiệu năng index B-tree | ❌ (phân mảnh, random I/O) | ✅ (insert gần như tuần tự) |
| Dễ dàng debug | ❌ (khó nhớ) | ✅ (có thể suy luận thời gian) |

**Kết luận:** UUID v7 là lựa chọn tối ưu cho cả phân tán, bảo mật và hiệu năng.

---

## 3. Tác động đến các thành phần

### 3.1. Entity và Repository
- Tất cả các `@Entity` có `@Id` kiểu `Long` sẽ chuyển sang `UUID`.
- Các repository method (ví dụ `findById`, `existsById`) vẫn hoạt động bình thường.
- Các truy vấn JPQL/query có tham chiếu đến id cần được điều chỉnh.

### 3.2. API (REST)
- Các endpoint trả về ID sẽ trả về chuỗi UUID thay vì số.
- Các endpoint nhận ID trong path/query sẽ nhận chuỗi UUID (ví dụ `/{id}` với `@PathVariable UUID id`).

### 3.3. Keyset Pagination
- Hiện tại nếu dùng `WHERE id > lastId` với `Long`, sẽ chuyển sang `WHERE id > lastUuid` (với UUID v7, so sánh chuỗi vẫn hoạt động vì thứ tự thời gian được mã hóa dưới dạng hex).
- **Lưu ý:** So sánh chuỗi UUID có thể chậm hơn số nguyên, nhưng vẫn chấp nhận được (có thể đánh index và sử dụng `BINARY(16)` để tối ưu).

### 3.4. Outbox Pattern (đã có trong `EMAIL_REDIS_STREAM_OUTBOX_PLAN.md`)
- Các bảng outbox sẽ dùng UUID làm id và aggregate_id.
- Cần đảm bảo các khóa ngoại (nếu có) cũng được chuyển.

### 3.5. Các module liên quan (auth, user, company, cv, application, ...)
- Tất cả các entity hiện có sẽ bị ảnh hưởng. Cần rà soát toàn bộ codebase.

---

## 4. Lưu ý về hiệu năng và storage

- UUID dạng `UUID` (16 byte) lớn hơn `Long` (8 byte), làm tăng kích thước index và storage ~30-40%.
- Tuy nhiên, với dự án quy mô vừa phải, tác động không đáng kể.
- Để tiết kiệm, có thể lưu UUID dạng `BINARY(16)` thay vì `VARCHAR(36)`.

---

## 5. Phương pháp tạo UUID v7 trong Java

### 5.1. Sử dụng thư viện `uuid-creator` (khuyến nghị)
```xml
<dependency>
    <groupId>com.github.f4b6a3</groupId>
    <artifactId>uuid-creator</artifactId>
    <version>5.1.0</version>
</dependency>
```
```java
import com.github.f4b6a3.uuid.UuidCreator;

UUID uuid = UuidCreator.getTimeOrdered();
```

### 5.2. Sử dụng Hibernate 6.4+ (nếu có)
```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
@UuidGenerator(style = UuidGenerator.Style.TIME)
private UUID id;
```

### 5.3. Sử dụng thư viện `java-uuid-generator` (alternate)
```xml
<dependency>
    <groupId>com.fasterxml.uuid</groupId>
    <artifactId>java-uuid-generator</artifactId>
    <version>4.0.1</version>
</dependency>
```

---

## 6. Kế hoạch triển khai từng bước

### Bước 0: Chuẩn bị
- Rà soát tất cả các entity và xác định danh sách các bảng cần chuyển.
- Tạo script migration (Flyway) để thay đổi kiểu dữ liệu của cột `id` và các khóa ngoại liên quan.
- Backup dữ liệu hiện có (nếu đang có dữ liệu).

### Bước 1: Thêm dependency và cấu hình
- Thêm `uuid-creator` vào `pom.xml`.
- Tạo utility class `UuidGenerator` để sinh UUID v7 tập trung.

### Bước 2: Cập nhật entity
- Sửa tất cả các entity: thay `Long id` thành `UUID id`.
- Đánh dấu `@Id` và nếu dùng tự động, cấu hình generator.
- Cập nhật các quan hệ `@OneToMany`, `@ManyToOne` với kiểu `UUID`.

### Bước 3: Cập nhật repository
- Các repository interface kế thừa `JpaRepository<Entity, UUID>` (thay `Long` bằng `UUID`).
- Các method tùy chỉnh có tham số `Long` chuyển sang `UUID`.

### Bước 4: Cập nhật service và controller
- Các service method nhận `Long` chuyển sang `UUID`.
- Các controller endpoint sử dụng `@PathVariable` với kiểu `UUID`.
- Cập nhật các DTO để trả về `UUID` dưới dạng `String`.

### Bước 5: Cập nhật keyset pagination
- Kiểm tra các truy vấn sử dụng `WHERE id > :lastId`.
- Sửa thành `WHERE id > :lastUuid` (với UUID v7, so sánh chuỗi hoặc binary đều hoạt động).
- Đảm bảo index trên cột id vẫn hiệu quả.

### Bước 6: Cập nhật các module khác
- Auth, user, company, cv, application, outbox, notification, review, ...
- Kiểm tra tất cả các file sử dụng ID.

### Bước 7: Migration dữ liệu (nếu có dữ liệu cũ)
- Tạo Flyway migration script:
```sql
-- Chuyển cột id từ BIGINT sang UUID (với giá trị mới)
ALTER TABLE users ADD COLUMN new_id UUID DEFAULT gen_random_uuid();
-- Cập nhật khóa ngoại tạm thời...
-- Xóa cột cũ, đổi tên cột mới...
```
- **Cảnh báo:** Migration dữ liệu phức tạp, cần test kỹ.

### Bước 8: Kiểm thử
- Unit test, integration test.
- Kiểm tra tất cả API, pagination, outbox.
- Kiểm tra hiệu năng và lỗi.

### Bước 9: Triển khai
- Deploy lên môi trường staging để kiểm tra.
- Sau khi ổn định, deploy production.

---

## 7. Các rủi ro và biện pháp giảm thiểu

| Rủi ro | Biện pháp |
|--------|-----------|
| Sự cố khi migration dữ liệu | Backup trước, thực hiện trên môi trường test trước. |
| Keyset pagination hoạt động kém | Đánh giá hiệu năng, có thể sử dụng composite key (created_at, id) nếu cần. |
| API thay đổi gây lỗi client | Thông báo trước, giữ API cũ trong thời gian chuyển tiếp (nếu cần). |
| Tăng kích thước DB | Theo dõi storage, có thể chuyển sang `BINARY(16)` để tiết kiệm. |

---

## 8. Kết luận

Chuyển đổi sang UUID v7 là một bước cải tiến quan trọng giúp hệ thống bảo mật và linh hoạt hơn, đồng thời vẫn duy trì được hiệu năng cho keyset pagination. Plan này cần được thực hiện tuần tự và có kế hoạch rollback rõ ràng.

**Bước tiếp theo:** Sau khi được duyệt, bắt đầu từ Bước 0 (rà soát và backup).