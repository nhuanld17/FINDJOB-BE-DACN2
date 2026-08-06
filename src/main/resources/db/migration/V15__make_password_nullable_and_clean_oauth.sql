-- V15__make_password_nullable_and_clean_oauth.sql

-- 1. Cho phép password NULL
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- 2. 🔑 DỌN DỮ LIỆU CŨ: Google user đang giữ hash UUID giả → set NULL
--    (Thiếu bước này = bug vẫn còn với user đăng ký Google trước đây!)
UPDATE users SET password = NULL WHERE auth_provider = 'GOOGLE';