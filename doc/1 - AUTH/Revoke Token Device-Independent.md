
# 1. Revoke token independent

---

Claim trong accesstoken và refresh token
- jti: định danh token
- sessionId: định danh phiên đăng nhập của thiết bị
- deviceId: định danh thiết bị
- sub: username (unique)

- 4 nhóm ley trong redis:
+ session:{sessionId} -> hash chứa username, deviceId, refreshJtiCurrent, status, createdAt, lastSeen, deviceName, ip, user Agent
+ user:sessions:{userId} -> set các sessionId của user có {userId} để thực hiện revoke all hoặc list các thiệt bị đang đăng nhập

---

## Login

## 1. UI chuẩn bị thông tin

Frontend lấy hoặc tạo `deviceId` rồi gửi kèm `username`, `password`, `deviceId`, `deviceName`; `deviceId` là client-instance identifier, còn `deviceName` chủ yếu để hiển thị trong màn quản lý thiết bị.

Ví dụ request:

```json
POST /auth/login
{
  "username": "alice",
  "password": "123456",
  "deviceId": "d-uuid-001",
  "deviceName": "Chrome on Windows"
}
```

## 2. Backend xác thực và tạo session

Sau khi username/password đúng, backend tạo:

- `sessionId` mới cho lần đăng nhập này.

- `accessToken` với `jti_access`.

- `refreshToken` với `jti_refresh`.

Hai token đều mang các claim:

- `sub`

- `jti`

- `sessionId`

- `deviceId`

## 3. Lưu session vào Redis (Có thể dùng lua script để đảm bảo lưu redis atomic)

Backend ghi:

- `session:{sessionId}` -> hash chứa `username`, `deviceId`, `refreshJtiCurrent`, `status=ACTIVE`, `createdAt`, `lastSeen`, `deviceName`, `ip`, `userAgent`.

- `user:sessions:{userId}` -> add `sessionId`.

**Ví dụ:**

```text
session:s-uuid-001
  username=name-001
  deviceId=d-uuid-001
  refreshJtiCurrent=rjti-001
  status=ACTIVE
  createdAt=...
  lastSeen=...
  deviceName=Chrome on Windows
  ip=1.2.3.4
  userAgent=Mozilla/5.0 ...

user:sessions:u1 = { s-uuid-001, s-uuid-002 }
```

## 4. Trả token cho client

Backend trả:

- access token

- refresh token  
  Client lưu để dùng cho request tiếp theo.


## Request thường

Khi client gọi API protected:

1. Gửi access token.

2. Backend verify chữ ký, `exp`

3. Check blacklist theo `jti`.

4. Đọc `sessionId` từ token, check `session:{sessionId}` có tồn tại và `status=ACTIVE` không.

5. Nếu hợp lệ thì cho qua và update `lastSeen`.[](https://www.youtube.com/watch?v=OpSU0VgfkL4)[](https://dev.to/shieldstring/session-management-rate-limiting-caching-using-redis-4poi)


Pseudo-flow:

```text
Request -> parse access token
        -> check exp/signature
        -> check blacklist:access:{jti}
        -> check session:{sessionId}.status == ACTIVE
        -> allow
```

Điểm hay của bước check session là khi bạn revoke 1 thiết bị, toàn bộ access/refresh của session đó bị vô hiệu theo trạng thái session, không cần chỉ nhìn vào từng token đơn lẻ.

## Refresh token

Khi access token hết hạn:

1. Client gọi `/auth/refresh` với refresh token hiện tại.

2. Backend verify chữ ký, `exp`

3. Check blacklist refresh theo `jti`.

4. Lấy `sessionId` trong refresh token rồi đọc `session:{sessionId}`.

5. Kiểm tra:

    - session còn `ACTIVE`

    - `refreshJtiCurrent` trong Redis đúng bằng `jti` của refresh token gửi lên.


Nếu đúng, backend thực hiện **refresh token rotation**:

- blacklist refresh `jti` cũ,

- sinh access token mới với `jti_access_new`,

- sinh refresh token mới với `jti_refresh_new`,

- update `session:{sessionId}.refreshJtiCurrent = jti_refresh_new`,

- update `lastSeen`.

Ví dụ logic:

```
refresh request
-> validate refresh token
-> compare jti == refreshJtiCurrent
-> blacklist old refresh jti
-> issue new access + new refresh
-> save new refreshJtiCurrent
-> return new tokens
```

Nếu refresh token cũ bị reuse sau khi đã rotate, đó là dấu hiệu token bị lộ; lúc này thường nên revoke luôn session đó để an toàn.

## Logout thiết bị hiện tại

Khi user logout trên chính thiết bị đang dùng:

1. Client gửi access token hoặc refresh token hiện tại.

2. Backend lấy ra `sessionId`, `jti`, `sub`.

3. Update `session:{sessionId}.status = REVOKED`.

4. Blacklist access token hiện tại theo `jti_access` nếu có.

5. Blacklist refresh token hiện tại theo `refreshJtiCurrent`.

6. Có thể xóa `session:{sessionId}` luôn hoặc giữ lại trạng thái `REVOKED` để audit.

7. Remove `sessionId` khỏi `user:sessions:{userId}` nếu bạn muốn coi như session kết thúc hẳn.[](https://www.youtube.com/watch?v=OpSU0VgfkL4)


Kết quả là thiết bị hiện tại không access tiếp được và cũng không refresh được nữa.

## Logout một thiết bị khác

Trong màn “thiết bị đã đăng nhập”, user chọn logout một thiết bị khác:

1. Backend nhận `sessionId` mục tiêu.

2. Kiểm tra session đó thuộc đúng user.

3. Đọc `session:{sessionId}`.

4. Set `status=REVOKED`.

5. Blacklist refresh `refreshJtiCurrent`.

6. Nếu bạn có lưu `currentAccessJti` cuối cùng thì blacklist luôn access đó; nếu không thì access đang sống của thiết bị đó sẽ chết khi hết hạn ngắn.


Ví dụ:

```json
POST /auth/logout-device
{
  "sessionId": "s-uuid-002"
}
```

Thiết bị A revoke thiết bị B, nhưng session của thiết bị A vẫn giữ nguyên vì mỗi thiết bị có `sessionId` riêng.

## Logout all

Khi user chọn “đăng xuất tất cả thiết bị”:

1. Lấy toàn bộ `sessionId` từ `user:sessions:{userId}`.

2. Duyệt từng session.

3. Set tất cả thành `REVOKED`.

4. Blacklist refresh token hiện hành của từng session.

5. Xóa hoặc remove toàn bộ `sessionId` khỏi `user:sessions:{userId}`.


Luồng này rất hợp cho case đổi mật khẩu, phát hiện nghi bị chiếm tài khoản, hoặc admin force logout toàn bộ.[](https://www.youtube.com/watch?v=OpSU0VgfkL4)[](https://stackoverflow.com/questions/37959945/how-to-destroy-jwt-tokens-on-logout)

## Trạng thái lỗi và thực tế triển khai

Có vài case thực tế cần nghĩ tới:

- Access token đã logout nhưng chưa blacklist được do Redis lỗi: session state vẫn là lớp chặn quan trọng, nên middleware phải check `session.status`.[](https://dev.to/shieldstring/session-management-rate-limiting-caching-using-redis-4poi)[](https://www.youtube.com/watch?v=OpSU0VgfkL4)

- Redis update fail sau khi DB đã commit: nên dùng outbox/retry nếu session metadata còn được lưu ở Postgres.

- Refresh token bị replay: nếu `jti` không còn khớp `refreshJtiCurrent`, reject request và có thể revoke cả session.


## Tóm tắt luồng

Bạn có thể hình dung toàn hệ thống như sau:

1. **Login**

    - UI gửi `username/password/deviceId/deviceName`.[](https://redis.antirez.com/community/session-management.html)

    - Server tạo `sessionId`, access token, refresh token.

    - Redis lưu `session:{sessionId}` và add vào `user:sessions:{userId}`.[](https://oneuptime.com/blog/post/2026-03-31-redis-mobile-session-management/view)

2. **Gọi API**

    - Kiểm tra access token, blacklist `jti`, session `ACTIVE`.[](https://www.youtube.com/watch?v=OpSU0VgfkL4)[](https://dev.to/shieldstring/session-management-rate-limiting-caching-using-redis-4poi)

3. **Refresh**

    - Kiểm tra refresh token.

    - So khớp `refreshJtiCurrent`.

    - Rotate refresh token và cập nhật session.

4. **Logout 1 thiết bị**

    - Revoke `sessionId` đó.

    - Blacklist token đang dùng của session đó.

5. **Logout all**

    - Lấy toàn bộ session của user.

    - Revoke tất cả.



