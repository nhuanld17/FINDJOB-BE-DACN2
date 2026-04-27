
---

**Bộ Test Chi Tiết: `register` + `verify-otp` + `resend-otp`**

Phần này mình viết lại theo đúng **API JSON hiện tại** của backend bạn, không theo flow JSP/redirect của project Servlet kia.

**Chuẩn chung**
- Base URL: `http://localhost:8080/api/v1/auth`
- Các API cần test:
  - `POST /register`
  - `POST /verify-otp`
  - `POST /resend-otp`
- Client phải gửi `credentials: include` nếu test qua FE.
- Cookie dùng trong flow: `pendingToken`
- Success body kỳ vọng:
```json
{
  "code": 1000,
  "message": "Success",
  "data": null
}
```
- Error body kỳ vọng:
```json
{
  "status": 400,
  "code": 3006,
  "message": "OTP is not true"
}
```

**Dữ liệu test gợi ý**
- Email mới: `otp_case_01@test.com`
- Username mới: `otp_case_01`
- Password hợp lệ: `12345678`
- Full name: `OTP Case 01`

---

**A. Test `POST /register`**

**TC-R01: Register mới hoàn toàn** - OK
- Pre-condition: email chưa tồn tại, username chưa tồn tại, Redis sạch cho user này.
- Request:
```json
{
  "username": "otp_case_01",
  "email": "otp_case_01@test.com",
  "password": "12345678",
  "confirmPassword": "12345678",
  "fullName": "OTP Case 01"
}
```
- Expected response:
  - HTTP `200`
  - `code=1000`
  - có header `Set-Cookie` chứa `pendingToken=...`
- Expected DB:
  - tạo user mới
  - `isActive=false`
  - `deleted=false`
  - `email` được lưu lowercase
  - `password` đã encode
- Expected Redis:
  - có `otp:{userId}`
  - có `otp:cooldown:{userId}`
  - có `otp:attempts:{userId}=1`
  - có `otp:wrong:{userId}=0`
  - có `pending:{token}`
  - có `pending:user:{userId}`

**TC-R02: Register với email đã active** - OK
- Pre-condition: DB đã có user `isActive=true`, `deleted=false` với email đó.
- Request: dùng lại email đó, username mới bất kỳ.
- Expected response:
  - HTTP `409`
  - `code=2002`
  - `message=Email already in use`
- Expected side effects:
  - không set `pendingToken`
  - không tạo OTP mới
  - không tăng attempts

**TC-R03: Register với email bị banned** - OK
- Pre-condition: DB có user `isActive=true`, `deleted=true`.
- Expected response:
  - HTTP `403`
  - `code=2007`
- Expected side effects:
  - không tạo OTP
  - không set cookie

**TC-R04: Register lại email inactive sau khi cooldown hết** - OK
- Pre-condition:
  - DB có user inactive với email này
  - `otp:cooldown:{userId}` đã hết
  - `otp:attempts` hiện tại < 5
- Request: cùng email, update `username/fullName/password`
- Expected response:
  - HTTP `200`
  - có `pendingToken`
- Expected DB:
  - không tạo user mới
  - update lại `username`
  - update `fullName`
  - update `password`
  - vẫn `isActive=false`
- Expected Redis:
  - OTP mới được tạo
  - `otp:attempts` tăng thêm 1
  - `otp:wrong=0`
  - token pending được rotate hoặc refresh

**TC-R05: Register lại email inactive khi cooldown còn** - OK
- Pre-condition:
  - user inactive đã có OTP
  - `otp:cooldown:{userId}` còn TTL > 0
- Expected response:
  - HTTP `200`
  - vẫn có `Set-Cookie pendingToken`
- Expected Redis:
  - `otp:{userId}` không đổi
  - `otp:attempts` không tăng
  - pending token được renew hoặc reuse
- Expected DB:
  - nếu code hiện tại update thông tin trước khi check cooldown thì username/fullName/password vẫn có thể được update

**TC-R06: Register khi `otp:attempts >= 5`** - OK
- Pre-condition:
  - user inactive tồn tại
  - Redis `otp:attempts:{userId}=5`
- Expected response:
  - HTTP `429`
  - `code=2008`
- Expected side effects:
  - không tạo OTP mới
  - không tăng attempts
  - không set cookie mới

**TC-R07: Username đã được user khác dùng** - OK
- Pre-condition:
  - email mới chưa tồn tại
  - username đã thuộc user khác
- Expected response:
  - HTTP `409`
  - `code=2003`
- Expected side effects:
  - không tạo user mới
  - không tạo OTP

**TC-R08: Password và confirmPassword không khớp** - OK
- Request:
```json
{
  "username": "otp_case_08",
  "email": "otp_case_08@test.com",
  "password": "12345678",
  "confirmPassword": "87654321",
  "fullName": "OTP Case 08"
}
```
- Expected response:
  - HTTP `400`
  - `code=2004`
- Expected side effects:
  - không tạo user
  - không tạo OTP

**TC-R09: Validation request body** - OK
- Case 1: `username=""`
- Case 2: `email="abc"`
- Case 3: `password="123"`
- Case 4: `fullName=""`
- Expected response:
  - HTTP `400`
  - body validation map đúng field lỗi

**TC-R10: Register 2 request gần như cùng lúc cùng email** - OK
- Pre-condition: email chưa tồn tại
- Action: bắn 2 request song song
- Expected:
  - 1 request thành công
  - request còn lại không nên thành `500`
- Note:
  - nếu hiện code chưa bắt unique constraint đẹp thì case này nên ghi nhận là “known gap”

---

**B. Test `POST /verify-otp`** - OK

**TC-V01: Verify đúng ngay lần đầu**
- Pre-condition: đã register thành công, lấy đúng OTP từ Redis/email, có cookie `pendingToken`
- Request:
```json
{ "otp": "123456" }
```
- Expected response:
  - HTTP `200`
  - `code=1000`
  - `Set-Cookie` clear `pendingToken`
- Expected DB:
  - user `isActive=true`
  - user có role `USER`
- Expected Redis:
  - xóa `otp`
  - xóa `otp:cooldown`
  - xóa `otp:attempts`
  - xóa `otp:wrong`
  - xóa `pending:user:{userId}`
  - xóa `pending:{token}`

**TC-V02: Verify sai 1 lần** - OK
- Pre-condition: OTP đang còn hạn
- Request: gửi mã sai
- Expected response:
  - HTTP `400`
  - `code=3006`
- Expected Redis:
  - `otp:wrong=1`
  - OTP gốc vẫn còn

**TC-V03: Sai 4 lần rồi đúng** - OK
- Action:
  - gọi verify với OTP sai 4 lần
  - lần 5 gửi OTP đúng
- Expected:
  - 4 lần đầu: `HTTP 400`, `code=3006`
  - `otp:wrong` tăng 1 -> 4
  - lần cuối `200 OK`
  - user được activate
  - Redis sạch

**TC-V04: Sai đến ngưỡng block** - OK
- Pre-condition: OTP còn hạn
- Action: verify sai liên tiếp đến khi chạm ngưỡng
- Expected theo code hiện tại:
  - lần sai trước ngưỡng: `OTP_INVALID`
  - từ request kế tiếp khi `wrong >= 5`: `HTTP 429`, `code=3004`
- Expected DB:
  - user chưa active

**TC-V05: Sau khi `wrong >= 5`, verify tiếp tục bị chặn** - OK
- Pre-condition: Redis `otp:wrong:{userId}=5`
- Request: verify bằng OTP đúng hoặc sai đều được
- Expected:
  - HTTP `429`
  - `code=3004`
  - không so OTP nữa

**TC-V06: OTP hết hạn** OK
- Pre-condition:
  - đã register
  - xóa `otp:{userId}` hoặc chờ TTL hết
- Request: verify với OTP cũ
- Expected:
  - HTTP `400`
  - `code=3005`

**TC-V07: Không gửi cookie `pendingToken`** OK
- Request: verify chỉ có body, không có cookie
- Expected:
  - HTTP `403`
  - `code=3003`

**TC-V08: Cookie còn nhưng `pending:{token}` đã mất** OK
- Pre-condition:
  - browser/client vẫn giữ cookie
  - Redis xóa `pending:{token}`
- Request: verify OTP đúng
- Expected:
  - HTTP `403`
  - `code=3003`
  - response clear cookie nếu service gọi `clearPendingCookie`

**TC-V09: Dùng OTP cũ sau khi resend** - OK
- Flow:
  - register lấy `OTP_1`
  - resend thành công lấy `OTP_2`
  - verify bằng `OTP_1`
- Expected:
  - HTTP `400`
  - `code=3006`
  - `otp:wrong` tăng 1
- Verify tiếp bằng `OTP_2`
- Expected:
  - `200 OK`

**TC-V10: Verify khi `otp:attempts = 5`** - OK
- Pre-condition:
  - Redis set `otp:attempts=5`
  - OTP đúng
- Expected:
  - vẫn `200 OK`
- Lý do:
  - code hiện tại chỉ block verify khi `attempts > 5`

**TC-V11: Verify khi `otp:attempts > 5`** - OK
- Pre-condition:
  - Redis set `otp:attempts=6`
- Expected:
  - HTTP `429`
  - `code=3010`

**TC-V12: OTP request body rỗng hoặc sai format** - OK
- Case:
  - `{ "otp": "" }`
  - `{ "otp": "123" }`
  - `{ "otp": "1234567" }`
- Expected:
  - HTTP `400`
  - lỗi validation field `otp`

---

**C. Test `POST /resend-otp`** - OK

**TC-S01: Resend thành công**
- Pre-condition:
  - đã register
  - cooldown hết
  - attempts < 5
- Request:
  - không có body
  - có cookie `pendingToken`
- Expected response:
  - HTTP `200`
  - `code=1000`
  - có `Set-Cookie pendingToken=...` mới
- Expected Redis:
  - OTP mới được ghi đè
  - `otp:wrong=0`
  - `otp:attempts` tăng thêm 1
  - cooldown mới 60s
  - token cũ bị xóa
  - token mới tồn tại

**TC-S02: Resend khi cooldown còn** - OK
- Pre-condition:
  - vừa register hoặc vừa resend xong
- Expected:
  - HTTP `400`
  - `code=3007`
- Expected Redis:
  - `otp:attempts` không tăng
  - OTP không đổi

**TC-S03: Resend khi đã chạm giới hạn gửi** - OK
- Pre-condition:
  - cooldown hết
  - `otp:attempts=5`
- Expected:
  - HTTP `429`
  - `code=2008`
- Expected side effects:
  - không gửi OTP mới
  - token không rotate

**TC-S04: Resend không có cookie** - OK
- Request: gọi API không gửi `pendingToken`
- Expected:
  - HTTP `403`
  - `code=3003`

**TC-S05: Resend với token hết hạn trong Redis** - OK
- Pre-condition:
  - cookie có nhưng xóa `pending:{token}`
- Expected:
  - HTTP `403`
  - `code=3003`
  - cookie bị clear nếu service xử lý

**TC-S06: Resend phải reset wrong count** - OK
- Flow:
  - register
  - verify sai 3 lần
  - chờ cooldown hết
  - resend
- Expected:
  - `otp:wrong` từ `3` về `0`

**TC-S07: Resend làm OTP cũ vô hiệu** - OK
- Flow:
  - register lấy `OTP_1`
  - resend lấy `OTP_2`
  - verify bằng `OTP_1`
- Expected:
  - `OTP_INVALID`
- Verify bằng `OTP_2`
- Expected:
  - success

**TC-S08: Token rotation sau resend**
- Flow:
  - register lấy `pendingToken_1`
  - resend thành công
- Expected Redis:
  - `pending:{pendingToken_1}` bị xóa
  - `pending:user:{userId}` trỏ tới token mới
  - `pending:{pendingToken_2}` tồn tại
- Expected response:
  - cookie ghi đè sang token mới

---

**D. End-to-End Flow Nên Test**

**TC-E01: Register -> verify success**
- Happy path cơ bản
- Assert đủ DB + Redis + cookie

**TC-E02: Register -> sai OTP vài lần -> verify đúng**
- Verify wrong count chưa block sớm

**TC-E03: Register -> OTP hết hạn -> resend -> verify đúng**
- Test recover flow chuẩn

**TC-E04: Register -> sai đến block -> resend -> verify đúng**
- Chứng minh resend reset `wrong`

**TC-E05: Register -> resend nhiều lần đến ngưỡng**
- Đến lúc `attempts=5`
- resend tiếp phải trả `OTP_SEND_LIMIT_REACHED`

**TC-E06: Register tab 1 -> register lại tab 2 cùng email**
- OTP cũ phải vô hiệu
- pending token cũ không còn dùng được

---

**E. Checklist Assert Chung Cho Mọi Case**
- HTTP status đúng
- `code` business đúng
- `message` đúng nhóm lỗi
- Cookie `pendingToken`:
  - được set khi cần
  - rotate khi resend
  - clear sau verify thành công hoặc session expired phù hợp
- DB:
  - `isActive`
  - `deleted`
  - `username/fullName/password`
  - role `USER`
- Redis:
  - `otp` có bị ghi đè đúng lúc không
  - `wrong` có tăng/reset đúng không
  - `attempts` có tăng/không tăng đúng không
  - pending token cũ có bị invalidate không

Nếu bạn muốn, bước tiếp theo mình sẽ chuyển nguyên bộ này thành **bảng test case chuẩn** với cột:
- `Test ID`
- `Mục tiêu`
- `Pre-condition`
- `Request`
- `Expected response`
- `Expected DB`
- `Expected Redis`

để bạn mang đi test hoặc đưa QA/AI khác dùng luôn.