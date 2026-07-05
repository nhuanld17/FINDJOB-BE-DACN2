# TEST

## Kịch bản kết hợp — Flow A → B → C


### Nhóm 1: Flow cơ bản

**TC-01: Đăng ký → verify thành công ngay lần đầu - OK rồi**
1. POST `/register` với thông tin hợp lệ, email chưa tồn tại
2. Kiểm tra Redis: 6 key có mặt (`otp:`, `otp:wrong:`, `otp:attempts:`, `otp:cooldown:`, `pending:`, `pending:user:`)
3. Kiểm tra cookie `PENDING_TOKEN` được set
4. Vào `/verify-otp`, nhập đúng OTP
5. **Kỳ vọng:** DB `status='active'`, toàn bộ 6 Redis key bị xóa, cookie `PENDING_TOKEN` bị xóa, response forward sang trang success overlay để user bấm về `/login`.

---

**TC-02: Đăng ký → nhập sai vài lần → nhập đúng - OK**
1. POST `/register` hợp lệ
2. Nhập sai OTP 3 lần → mỗi lần báo "Còn N lần thử", `otp:wrong` tăng dần (1→2→3)
3. Nhập đúng OTP lần 4
4. **Kỳ vọng:** Activate thành công, Redis sạch, response forward sang trang success overlay để user bấm về `/login`.

---

**TC-03: Đăng ký → OTP hết hạn → resend → verify thành công - OK **
1. POST `/register` hợp lệ
2. Chờ > 5 phút (hoặc xóa `otp:{userId}` trong Redis thủ công để simulate)
3. Vào `/verify-otp` → thấy thông báo "OTP đã hết hạn", nút "Gửi lại" active
4. Bấm "Gửi lại" → `otp:attempts` tăng từ 1 lên 2, OTP mới được gửi
5. Nhập OTP mới đúng
6. **Kỳ vọng:** Activate thành công

---

### Nhóm 2: Brute force + Resend kết hợp

---

**TC-04: Nhập sai 5 lần → resend → nhập đúng - OK**

**Mục tiêu:** Xác nhận rằng `resendOtp()` reset `otp:wrong` về 0, cho phép user tiếp tục verify bình thường với OTP mới.

**Điều kiện tiên quyết:** Khởi đầu sạch — không có key Redis nào cho userId này.

**Các bước thực hiện:**

1. POST `/register` với thông tin hợp lệ → server tạo OTP_1, set:
   - `otp:{id} = OTP_1` (TTL 5 phút)
   - `otp:wrong:{id} = 0` (TTL 5 phút)
   - `otp:attempts:{id} = 1` (TTL 1 giờ)
   - `otp:cooldown:{id} = 1` (TTL 60 giây)
   - Cookie `PENDING_TOKEN` được đặt (MaxAge 600s)
   - Redirect sang `/verify-otp`

2. **Chờ 60 giây** để `otp:cooldown` hết hạn → nút "Gửi lại" sẽ active (TTL trả về -2 → `resendCooldown <= 0`).

   > **Lý do phải chờ:** Nếu nhập sai 5 lần trong vòng 60s đầu, sau khi bị block brute force, server trả về `resendCooldown > 0` → nút "Gửi lại" vẫn bị `disabled`. User phải thêm bước chờ cooldown, làm TC phức tạp hơn. Để test đúng trọng tâm, nên chờ cooldown hết rồi mới bắt đầu nhập.

3. Nhập sai OTP 4 lần liên tiếp (lần 1 → 4):
   - Mỗi lần: `incrementWrong()` → `otp:wrong:{id}` tăng thêm 1 (TTL không bị reset)
   - Sau lần 4: `otp:wrong:{id} = 4`
   - JSP hiển thị `errorOtp` = "OTP không chính xác. Còn 1 lần thử."
   - `otpExpired = false` → verify form vẫn active

4. Nhập sai lần thứ 5:
   - `incrementWrong()` → `otp:wrong:{id} = 5`
   - `remaining = 5 - 5 = 0` → handler set `otpExpired = true`
   - JSP hiển thị `errorOtp` = "OTP bị huỷ do nhập sai quá nhiều lần. Vui lòng gửi lại."
   - 6 ô nhập và nút "Xác minh" bị `disabled`
   - Nút "Gửi lại" **active** (vì cooldown đã hết từ bước 2)
   - Trạng thái Redis: `otp:{id}` vẫn còn (OTP_1 chưa hết hạn), `otp:wrong:{id} = 5`

5. Bấm "Gửi lại" → POST `/resend-otp`:
    - Kiểm tra `getCooldownTtl() <= 0` → pass
    - Kiểm tra `getResendAttempts() = 1 < 5` → pass
    - Gọi `resendOtp()`:
       - `otp:{id}` = OTP_2 mới (ghi đè OTP_1)
       - `otp:wrong:{id}` = "0" (reset brute force)
       - `otp:cooldown:{id}` = "1" (TTL 60 giây)
       - `otp:attempts:{id}` tăng lên 2, TTL 1 giờ giữ nguyên theo fixed window
   - Handler redirect về `/verify-otp?resent=true`, JSP hiển thị banner thành công "Mã OTP mới đã được gửi..."
    - `otpExpired = false` → form nhập OTP active trở lại
    - Nút "Gửi lại" bị `disabled` 60 giây (countdown hiển thị)

6. Nhập OTP_2 đúng → POST `/verify-otp`:
   - `getWrongCount() = 0 < 5` → pass brute force guard
   - `getStoredOtp() = OTP_2` → còn hạn
   - `stored.equals(submitted)` → true
   - `activateUser(userId)` → `users.status = 'active'`
   - `clearAll()` xóa toàn bộ key Redis
   - `clearPendingCookie()` → cookie MaxAge = 0
   - Forward sang trang success overlay

**Kỳ vọng:** Activate thành công, hiển thị success overlay để user bấm về trang login.

---

**TC-05: Nhập sai 3 lần → resend → nhập sai 2 lần → nhập đúng - OK - OK**

**Mục tiêu:** Xác nhận `otp:wrong` được reset hoàn toàn về 0 sau mỗi lần resend, không cộng dồn với số lần sai từ OTP trước.

**Các bước thực hiện:**

1. POST `/register` hợp lệ → OTP_1 được tạo. Chờ 60s để cooldown hết.

2. Nhập sai OTP 3 lần:
   - Sau lần 3: `otp:wrong:{id} = 3`
   - JSP hiển thị: "OTP không chính xác. Còn **2** lần thử."
   - `otpExpired = false` → form vẫn active

3. Bấm "Gửi lại" (cooldown đã hết từ bước 1):
    - `resendOtp()` gọi:
       - `otp:wrong:{id}` **reset về "0"** (không giữ lại số 3 từ trước)
       - `otp:{id}` = OTP_2 mới
       - `otp:cooldown:{id}` = 60 giây
       - `otp:attempts:{id}` = 2 (TTL 1 giờ giữ nguyên)
    - JSP hiển thị success, form active, countdown 60s

4. Chờ 60s để cooldown hết. Nhập sai OTP_2 lần 1:
   - `otp:wrong:{id}` tăng từ 0 → 1
   - JSP: "Còn **4** lần thử." ← **không phải "Còn 1 lần thử"**, vì `wrong` đã reset về 0 ở bước 3

5. Nhập sai OTP_2 lần 2:
   - `otp:wrong:{id} = 2`
   - JSP: "Còn **3** lần thử."

6. Nhập đúng OTP_2:
   - `getWrongCount() = 2 < 5` → pass
   - `stored.equals(submitted)` → true
   - Activate thành công, forward sang trang success overlay

**Kỳ vọng:** Activate thành công.

**Điểm cần verify đặc biệt:** Ở bước 4, số lần thử còn lại phải là 4 (không phải 1). Nếu server cộng dồn `otp:wrong`, bước này sẽ hiện "Còn 1 lần thử" — đó là bug.

---

**TC-06: Nhập sai 5 lần → resend đến ngưỡng attempts → bị khoá cả hai - OK**

**Mục tiêu:** Xác nhận rằng khi cả brute force (`otp:wrong >= 5`) lẫn resend limit (`otp:attempts >= 5`) đều đạt ngưỡng, user hoàn toàn bị khóa trong phiên đó.

**Timeline bắt buộc phải chờ:** Mỗi resend đặt lại `otp:cooldown` 60 giây. Vì `otp:attempts` đã là 1 ngay từ lần register đầu tiên, user chỉ resend thành công tối đa 4 lần trước khi bị chặn ở lần gửi lại kế tiếp.

**Các bước thực hiện:**

1. POST `/register` hợp lệ → OTP_1. Chờ 60s để cooldown hết.

2. Nhập sai OTP_1 **5 lần** → bị block brute force:
   - `otp:wrong:{id} = 5`
   - JSP: "OTP bị huỷ do nhập sai quá nhiều lần. Vui lòng gửi lại."
   - Verify form disabled, nút "Gửi lại" active

3. Bấm "Gửi lại" lần 1 → OTP_2, `otp:wrong` reset về 0, `otp:attempts = 2`. Chờ 60s.

4. Nhập sai OTP_2 **5 lần** → bị block brute force lại:
   - `otp:wrong:{id} = 5`
   - Nút "Gửi lại" active (cooldown đã hết)

5. Bấm "Gửi lại" lần 2 → OTP_3, `otp:wrong` reset về 0, `otp:attempts = 3`. Chờ 60s.

6. Lặp lại bước 4–5 thêm 2 lần nữa → sau lần resend thứ 4:
   - `otp:attempts:{id} = 5`, TTL giảm dần theo fixed window 1 giờ
   - OTP_5 mới được tạo, `otp:wrong = 0`
   - Verify form active (có thể nhập OTP_5 đúng ở đây)

7. **Nhập sai OTP_5 thêm 5 lần** → `otp:wrong = 5` → verify form disabled.

8. Bấm "Gửi lại" lần 5:
   - `getResendAttempts() = 5 >= MAX_RESEND_ATTEMPTS(5)` → **bị chặn**
   - Handler set `resendBlocked=true`; JSP hiển thị block message "Gửi lại quá nhiều lần. Vui lòng thử lại sau ..." (không đi qua `errorOtp`)
   - `otpExpired` = `isVerifyBlocked(userId)`; ở kịch bản này sẽ là `true` vì `otp:wrong = 5` (dù OTP_5 còn hạn)

**Trạng thái Redis cuối:**
- `otp:wrong:{id} = 5` → verify bị chặn
- `otp:attempts:{id} = 5` → resend bị chặn
- `otp:cooldown:{id}` — đã hết (vì không có resend thành công ở bước 8)

**Kỳ vọng:** User không thể làm gì tiếp. Cách duy nhất để ra khỏi trạng thái này là chờ `otp:attempts:{id}` hết TTL (~55 phút còn lại). Tuy nhiên lúc đó `pending:{token}` đã hết hạn từ lâu (10 phút), nên dù `otp:attempts` reset, user vẫn phải **đăng ký lại** từ đầu. Và vì `otp:attempts` không bị reset khi đăng ký lại (là thiết kế có chủ ý), user thực sự phải chờ hết 1 giờ rồi mới đăng ký thành công được.

---

**TC-07: Resend → nhập OTP cũ (đã bị ghi đè) → nhập OTP mới đúng - OK**

**Mục tiêu:** Xác nhận rằng sau khi resend, OTP cũ không còn hiệu lực. Nhập OTP cũ sẽ bị tính là sai.

**Các bước thực hiện:**

1. POST `/register` hợp lệ → email nhận được **OTP_1** (ghi lại). Chờ 60s để cooldown hết.

2. Bấm "Gửi lại" (không nhập gì cả):
   - `resendOtp()` gọi: ghi đè `otp:{id}` = **OTP_2** mới
   - `otp:wrong:{id}` reset về "0"
   - `otp:attempts:{id} = 2` (TTL 1 giờ giữ nguyên)
   - `otp:cooldown:{id}` = 60s
   - Email mới với OTP_2 được gửi đi bất đồng bộ
   - JSP hiển thị "Mã OTP mới đã được gửi...", countdown 60s bắt đầu

3. Không đọc email mới, **nhập OTP_1 (cũ)** mà user còn nhớ:
   - `getStoredOtp() = OTP_2` (trong Redis chỉ còn OTP_2)
   - `OTP_2.equals(OTP_1)` → **false**
   - `incrementWrong()` → `otp:wrong:{id} = 1`
   - JSP hiển thị: "OTP không chính xác. Còn **4** lần thử."

4. Mở email mới, lấy **OTP_2** (mới), nhập đúng:
   - `getWrongCount() = 1 < 5` → pass brute force guard
   - `getStoredOtp() = OTP_2`
   - `stored.equals(submitted)` → **true**
   - Activate thành công
   - `clearAll()` dọn sạch Redis
   - Forward sang trang success overlay

**Kỳ vọng:** Activate thành công. OTP cũ bị tính là sai và tăng `otp:wrong`, nhưng vì chỉ sai 1 lần nên không ảnh hưởng đến kết quả.

**Điểm cần verify đặc biệt:** Sau bước 2 (resend), `otp:{id}` trong Redis phải là OTP_2, không còn OTP_1. Có thể kiểm tra bằng `redis-cli GET otp:{userId}` trước và sau khi resend.

---

### Nhóm 3: Đăng ký lại (reuse record)

**TC-08: Đăng ký lại cùng email sau khi hết cooldown → OTP mới được gửi**
1. POST `/register` lần 1 → OTP_1 được gửi, `otp:attempts=1`, cooldown 60s
2. **Chờ > 60s** để cooldown hết
3. POST `/register` lần 2 cùng email (thông tin có thể khác)
4. **Kỳ vọng:** OTP_2 mới được gửi đến email, `otp:attempts=2`, cooldown 60s mới bắt đầu, token mới được tạo, cookie được ghi đè
5. Nhập OTP_2 đúng → Activate thành công, DB cập nhật theo thông tin lần 2

---

**TC-09: Đăng ký lại cùng email sau khi bị brute force block — `otp:wrong` không bị reset - OK**

**Mục tiêu:** Verify rằng đăng ký lại cùng email không reset counter nhập sai, tránh bypass brute force bằng cách reload register.

**Điều kiện tiên quyết:** Redis sạch cho email test.

**Các bước:**

| # | Hành động | Redis state sau bước |
|---|---|---|
| 1 | POST `/register` với email A (hợp lệ) | `otp:{uid}=XXXXXX` TTL 5p, `otp:wrong:{uid}=0` TTL 5p, `otp:attempts:{uid}=1` TTL 1h, `otp:cooldown:{uid}=1` TTL 60s |
| 2 | Đợi hết cooldown (60s) | `otp:cooldown:{uid}` xóa tự nhiên |
| 3 | Vào `/verify-otp`, nhập sai OTP 5 lần | `otp:wrong:{uid}=5` |
| 4 | Lần sai thứ 5 → trang hiện: *"OTP bị huỷ do nhập sai quá nhiều lần. Vui lòng gửi lại."*, OTP input bị disabled | `otp:{uid}` vẫn còn đến khi hết TTL (không bị xóa ngay) |
| 5 | Quay lại POST `/register` cùng email A | `setupOtp()` gọi `setex(KEY_OTP,...)` → sinh OTP mới, nhưng `otp:wrong` không bị reset (vì `if (jedis.get(KEY_WRONG) == null)` → key vẫn tồn tại với `wrong=5`) |
| 6 | Redirect sang `/verify-otp` | Cookie vẫn hợp lệ |
| 7 | GET `/verify-otp` | Server gọi `getWrongCount(uid)` → 5 ≥ MAX_WRONG_ATTEMPTS → render trang với OTP input **disabled**, thông báo *"OTP bị huỷ do nhập sai quá nhiều lần. Vui lòng gửi lại."* |

**Kỳ vọng:**
- Bước 7: OTP input disabled ngay khi vào trang, không nhập được
- `otp:wrong:{uid}` = 5 (không bị reset)
- Nút **Gửi lại mã** hoạt động bình thường (đây là cách thoát hợp lệ)

**Kỳ vọng sai (nếu có bug):**
- OTP input enabled → user nhập được OTP mới → bypass brute force limit

> **Lưu ý quan trọng bước 5:** `setupOtp()` chỉ khởi tạo `otp:wrong` khi `jedis.get(KEY_WRONG) == null`. Vì `otp:wrong` vẫn còn TTL (5 phút từ lần sai cuối cùng) nên key vẫn tồn tại → không bị reset.

---

**TC-10: Đăng ký lại sau khi resend hết lượt — attempts không bị reset**
1. POST `/register` hợp lệ
2. Resend thành công 4 lần; ở request resend kế tiếp thì bị chặn (vì `otp:attempts` đã đạt 5)
3. Quay lại POST `/register` cùng email (reuse)
4. **Kỳ vọng:** RegisterHandler chặn ngay trên trang register với thông báo "Email này đã gửi OTP quá nhiều lần...", không redirect sang `/verify-otp`
5. **Kỳ vọng thêm:** `otp:attempts` không bị reset cho đến khi hết TTL cửa sổ 1 giờ

---


**TC-11: Token pending hết hạn trong khi user đang trên trang `/verify-otp`**

**Mục tiêu:** Verify rằng khi pending token hết hạn (> 10 phút), mọi thao tác trên `/verify-otp` đều bị chặn và thông báo phiên hết hạn.

**Điều kiện tiên quyết:** Redis sạch cho email test.

**Các bước:**

| # | Hành động | Kết quả |
|---|---|---|
| 1 | POST `/register` hợp lệ | Cookie `PENDING_TOKEN` được set (MaxAge=600s), `pending:{token}` trong Redis TTL 10p |
| 2 | Chờ > 10 phút (hoặc xóa `pending:{token}` thủ công trong Redis) | `pending:{token}` hết hạn, bị xóa. Cookie trong browser cũng hết hạn (MaxAge=600s) |
| 3 | GET `/verify-otp` (hoặc refresh trang) | Cookie đã hết hạn → `readPendingCookie()` trả về `null` → redirect `/login?expired=true` |
| 4 | POST `/verify-otp` (submit OTP thủ công qua form cũ còn trong cache) | Tương tự: cookie null → redirect `/login?expired=true` |

**Kỳ vọng:**
- Bước 3 + 4: Redirect về `/login?expired=true` để hiển thị thông báo phiên đã hết hạn
- Không xử lý OTP, không có lỗ hổng bypass

**Kỳ vọng sai (nếu có bug):**
- Trang `/verify-otp` vẫn load bình thường với OTP form mà không kiểm tra token

---

### Nhóm 4: Token/cookie hết hạn giữa chừng

**TC-12: Đăng ký → tự xóa cookie → vào `/verify-otp` - OK**
1. POST `/register` hợp lệ
2. Mở DevTools → xóa cookie `PENDING_TOKEN`
3. GET `/verify-otp`
4. **Kỳ vọng:** Redirect `/login?expired=true`

---

**TC-13: Đăng ký → xóa cookie → POST `/verify-otp` trực tiếp - OK**
1. POST `/register` hợp lệ
2. Xóa cookie `PENDING_TOKEN`
3. POST `/verify-otp` với OTP bất kỳ (dùng Postman hoặc form trực tiếp)
4. **Kỳ vọng:** Redirect `/login?expired=true`, không verify được gì

---

**TC-14: Cookie còn nhưng pending token hết hạn trong Redis - OK**
1. POST `/register` hợp lệ
2. Xóa `pending:{token}` trong Redis thủ công (simulate hết 10 phút)
3. POST `/verify-otp` với đúng OTP
4. **Kỳ vọng:** Cookie bị xóa, redirect `/login?expired=true`

---

**TC-15: Đăng ký tab 1 → đăng ký tab 2 cùng email → quay lại tab 1 submit OTP cũ - OK**

**Mục tiêu:** Xác nhận OTP_1 bị vô hiệu hóa khi đăng ký lại từ tab khác.

1. Tab 1: POST `/register` → nhận OTP_1 qua email, cookie `PENDING_TOKEN=token-A`
2. Tab 2: POST `/register` cùng email (chờ > 60s để qua cooldown):
   - `setupOtp()` ghi đè `otp:{uid}` = OTP_2, token-A bị xóa, token-B được tạo
   - Cookie `PENDING_TOKEN` bị ghi đè thành token-B (cả 2 tab đều nhận token-B)
3. Quay lại tab 1 (form cũ đang mở), nhập **OTP_1** (email cũ), submit
4. **Kỳ vọng:**
   - Server đọc cookie → token-B → hợp lệ (userId tìm được)
   - `getStoredOtp()` = OTP_2 (đã bị ghi đè)
   - OTP_1 ≠ OTP_2 → sai → `otp:wrong` tăng lên 1, thông báo "OTP không chính xác. Còn 4 lần thử."
5. Mở email lần 2, lấy OTP_2, nhập đúng → Activate thành công

**Kỳ vọng sai (nếu có bug):** OTP_1 vẫn còn hiệu lực → verify thành công bằng OTP cũ.

---

**TC-16: Bypass frontend — POST thẳng vào `/resend-otp` trong khi cooldown vẫn còn - OK**

**Mục tiêu:** Xác nhận backend cũng chặn resend trong cooldown, không chỉ dựa vào disabled button phía frontend.

**Chuẩn bị:**
1. POST `/register` hợp lệ trên trình duyệt → cooldown 60s bắt đầu
2. Mở DevTools → tab **Application** → **Cookies** → copy giá trị `PENDING_TOKEN`

**Các bước trên Postman:**

| Field           | Giá trị                                       |
| --------------- | --------------------------------------------- |
| Method          | `POST`                                        |
| URL             | `http://localhost:8080/ShopHub/resend-otp`    |
| Tab **Headers** | `Cookie` : `PENDING_TOKEN=<giá trị vừa copy>` |
| Body            | _(để trống)_                                  |

3. Gửi request **trong vòng 60s** kể từ lúc đăng ký

**Kỳ vọng:**
- Response trả về HTML của trang `/verify-otp` có chứa thông báo *"Vui lòng chờ X giây trước khi gửi lại."*
- Kiểm tra Redis: `redis-cli GET otp:attempts:{userId}` vẫn = `1` (không tăng)
- Kiểm tra Redis: `redis-cli GET otp:{userId}` vẫn là OTP gốc (không bị ghi đè)

**Kỳ vọng sai (nếu có bug):** Response chứa thông báo gửi OTP thành công, `otp:attempts` tăng lên 2 → backend không kiểm tra cooldown, attacker spam resend bỏ qua giới hạn.

---

**TC-17: Chờ hết cooldown → resend → bypass frontend POST lại ngay — cooldown bắt đầu lại - OK**

**Mục tiêu:** Xác nhận sau mỗi resend thành công, cooldown 60s được reset lại và backend chặn resend tiếp theo dù bypass UI.

**Các bước:**

1. POST `/register` hợp lệ → `otp:attempts=1`, cooldown 60s
2. Chờ 60s để cooldown hết
3. Vào `/verify-otp`, bấm **"Gửi lại"** → resend thành công:
   - `otp:attempts=2`, cooldown 60s mới bắt đầu
   - Cookie `PENDING_TOKEN` đổi sang giá trị mới (token rotation)
   - Nút "Gửi lại" bị disabled, countdown hiển thị
4. Mở DevTools → copy giá trị `PENDING_TOKEN` **mới**
5. Dùng Postman POST `/resend-otp` ngay lập tức với cookie mới (tương tự TC-16)
6. **Kỳ vọng:**
   - Backend kiểm tra `getCooldownTtl() > 0` → chặn
   - Response HTML chứa thông báo *"Vui lòng chờ X giây trước khi gửi lại."*
   - `otp:attempts` vẫn = `2` (không tăng)
   - `otp:{uid}` không bị ghi đè

**Lưu ý bước 4:** Phải copy token **mới** sau resend vì token cũ đã bị xóa khỏi Redis (token rotation). Dùng token cũ → Postman nhận redirect `/login?expired=true`.

----
**TC-18: Đăng ký lại cùng email trong khi cooldown vẫn còn — không gửi OTP mới - OK**
1. POST `/register` lần 1 → OTP_1 được gửi, `otp:attempts=1`, cooldown 60s bắt đầu
2. **Ngay lập tức** (trong vòng 60s) POST `/register` lần 2 cùng email
3. **Kỳ vọng:**
   - Không gửi email OTP mới
   - `otp:attempts` **không tăng** (vẫn = 1)
   - Redirect sang `/verify-otp` với countdown cooldown vẫn đang chạy
   - `otp:{uid}` trong Redis vẫn là OTP_1 (chưa bị ghi đè)
4. Nhập OTP_1 (email lần 1) đúng → Activate thành công

----
**TC-19: Đăng ký lại cùng email sau khi resend hết lượt — hiện thông báo bị khoá ngay trên trang register - OK**
1. POST `/register` hợp lệ, resend thành công 4 lần rồi thử lần 5 → `otp:attempts=5`, request resend lần 5 bị block
2. POST `/register` lại cùng email
3. **Kỳ vọng:**
   - RegisterHandler kiểm tra `getResendAttempts() >= 5` → hit Case 1
   - Hiện thông báo *"Email này đã gửi OTP quá nhiều lần. Vui lòng thử lại sau X phút."* ngay trên trang register
   - Không redirect sang `/verify-otp`, không gửi OTP mới