
---

Claim trong accesstoken và refresh token
- jti: định danh token
- sub: username (unique)
- roles: danh sách quyền
- sessionId: định danh phiên đăng nhập của thiết bị
- deviceId: định danh thiết bị
- iat / exp: thời điểm phát hành / hết hạn

- 4 nhóm key trong redis:

+ session:{sessionId} -> hash chứa username, deviceId, refreshJtiCurrent, status, createdAt, lastSeen, deviceName, ip, userAgent
+ user:sessions:{userId} -> set các sessionId của user có {userId} để thực hiện revoke all hoặc list các thiết bị đang đăng nhập
+ blacklist:access:{jti} -> TTL = thời gian còn lại của Accesstoken khi bị thu hồi
+ blacklist:refresh:{jti} -> TTL = thời gian còn lại của Refreshtoken khi bị thu hồi

---

## Login

## 1. UI chuẩn bị thông tin

Frontend lấy hoặc tạo `deviceId` rồi gửi kèm `email`, `password`, `deviceId`, `deviceName`; `deviceId` là client-instance identifier, còn `deviceName` chủ yếu để hiển thị trong màn quản lý thiết bị.

Ví dụ request:

```json
POST /api/v1/auth/login
{
  "email": "alice@example.com",
  "password": "123456",
  "deviceId": "d-uuid-001",
  "deviceName": "Chrome on Windows"
}
```

## 2. Backend xác thực và tạo session

Sau khi email/password đúng, backend tạo:

- `sessionId` mới cho lần đăng nhập này.
    
- `accessToken` với `jti_access`.
    
- `refreshToken` với `jti_refresh`.
    
Hai token đều mang các claim:

- `sub`
    
- `jti`
    
- `sessionId`
    
- `deviceId`

## 3. Lưu session vào Redis (Có thể dùng lua script để đảm bảo lưu redis atomic)

Backend ghi:

- `session:{sessionId}` -> hash chứa `username`, `deviceId`, `refreshJtiCurrent`, `status=ACTIVE`, `createdAt`, `lastSeen`, `deviceName`, `ip`, `userAgent`.
    
- `user:sessions:{userId}` -> add `sessionId`.

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

- access token — nằm trong body response; FE tự giữ để đính vào header `Authorization: Bearer` mỗi request.
    
- refresh token — nằm trong cookie `HttpOnly` (`Secure`, `SameSite=Strict`); trình duyệt tự quản, chỉ gửi tới `/refresh-token` và `/logout`.
    

## Request thường

Khi client gọi API protected:

1. Gửi access token.
    
2. Backend verify chữ ký, `exp`
    
3. Check blacklist theo `jti`.
    
4. Đọc `sessionId` từ token, check `session:{sessionId}` có tồn tại và `status=ACTIVE` không.
    
5. Nếu hợp lệ thì cho qua và update `lastSeen`.
    

Pseudo-flow:

```text
Request -> parse access token
        -> check exp/signature
        -> check blacklist:access:{jti}
        -> check session:{sessionId} tồn tại & status == ACTIVE
        -> check session.username == sub & session.deviceId == deviceId(claim)
        -> check user (DB tươi) chưa bị ban/vô hiệu
        -> allow + update lastSeen
```

Điểm hay của bước check session là khi bạn revoke 1 thiết bị, toàn bộ access/refresh của session đó bị vô hiệu theo trạng thái session, không cần chỉ nhìn vào từng token đơn lẻ.

## Refresh token

Khi access token hết hạn:

1. Client gọi `/api/v1/auth/refresh-token`, refresh token tự động gửi kèm qua cookie.
    
2. Backend verify chữ ký, `exp`
    
3. Check blacklist refresh theo `jti`.
    
4. Lấy `sessionId` trong refresh token rồi đọc `session:{sessionId}`.
    
5. Kiểm tra:
    
    - session còn `ACTIVE`
        
    - `refreshJtiCurrent` trong Redis đúng bằng `jti` của refresh token gửi lên.
        

Nếu đúng, backend thực hiện **refresh token rotation**:

- sinh access token mới với `jti_access_new`,
    
- sinh refresh token mới với `jti_refresh_new`,
    
- update `session:{sessionId}.refreshJtiCurrent = jti_refresh_new`,
    
- update `lastSeen`.
    
Ví dụ logic:

```
refresh request
-> validate refresh token
-> compare jti == refreshJtiCurrent
-> issue new access + new refresh
-> save new refreshJtiCurrent (refresh token cũ tự hết hiệu lực do jti lệch)
-> return new tokens
```

> **Lưu ý (quan trọng):** rotation **KHÔNG** blacklist refresh token cũ — nó tự mất hiệu lực vì `jti` không còn khớp `refreshJtiCurrent`. Nếu cố tình blacklist nó, guard blacklist (bước 3) sẽ chặn trước và làm mất khả năng phát hiện reuse ở bước 5 — xem `4. Refresh token.md` §5.

Nếu refresh token cũ bị reuse sau khi đã rotate (`jti` không còn khớp `refreshJtiCurrent`), đó là dấu hiệu token bị lộ. Backend xử lý: set `session:{sessionId}.status = REVOKED`, blacklist chính refresh token bị replay đó, và trả `3013 TOKEN_REUSE_DETECTED` → toàn bộ phiên bị vô hiệu, buộc đăng nhập lại.

## Logout thiết bị hiện tại

Khi user logout trên chính thiết bị đang dùng:

1. Client gửi refresh token qua cookie; access token (tùy chọn) qua header `Authorization: Bearer`.
    
2. Backend đọc refresh token từ cookie → lấy `sessionId`, `username`; đọc access token từ header nếu có.
    
3. **Xóa hẳn** `session:{sessionId}` bằng `deleteSession` (DEL key) — KHÔNG phải set `status = REVOKED`.
    
4. Blacklist access token hiện tại theo `jti_access` nếu có.
    
5. Blacklist refresh token hiện tại theo `refreshJtiCurrent`.
    
6. Vì xóa hẳn nên mất metadata phiên (khác reuse-detection — giữ lại `REVOKED` để audit). Cookie `refreshToken` phía client luôn bị xóa (idempotent, thực ra chạy đầu tiên).
    
7. Remove `sessionId` khỏi `user:sessions:{userId}` (best-effort: không tra ra user thì bỏ qua, không lỗi).
    

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

- Access token đã logout nhưng chưa blacklist được do Redis lỗi: session state vẫn là lớp chặn quan trọng, nên middleware phải check `session.status`.
    
- Redis update fail sau khi DB đã commit: nên dùng outbox/retry nếu session metadata còn được lưu ở Postgres.
    
- Refresh token bị replay: nếu `jti` không còn khớp `refreshJtiCurrent`, reject request và có thể revoke cả session.
    

## Tóm tắt luồng

Bạn có thể hình dung toàn hệ thống như sau:

1. **Login**
    
    - UI gửi `email/password/deviceId/deviceName`.
        
    - Server tạo `sessionId`, access token, refresh token.
        
    - Redis lưu `session:{sessionId}` và add vào `user:sessions:{userId}`.
        
2. **Gọi API**
    
    - Kiểm tra access token, blacklist `jti`, session `ACTIVE`.
        
3. **Refresh**
    
    - Kiểm tra refresh token.
        
    - So khớp `refreshJtiCurrent`.
        
    - Rotate refresh token và cập nhật session.
        
4. **Logout 1 thiết bị**
    
    - Revoke `sessionId` đó.
        
    - Blacklist token đang dùng của session đó.
        
5. **Logout all**
    
    - Lấy toàn bộ session của user.
        
    - Revoke tất cả.

