-- V7: employer tự đăng ký qua flow register + verify OTP.

-- 1) city chưa biết lúc auto-create company, employer bổ sung sau qua PUT.
alter table companies
    alter column city drop not null;

-- 2) Lưu intent đăng ký (loại tài khoản + tên công ty) trên user inactive,
--    được đọc & clear khi verify OTP thành công.
alter table users
    add column pending_account_type varchar(20),
      add column pending_company_name varchar(255);