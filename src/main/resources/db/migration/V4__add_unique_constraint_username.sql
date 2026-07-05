-- Enforce username uniqueness at the database level.
--
-- Lý do: ddl-auto=none nên @UniqueConstraint trong entity User KHÔNG được áp dụng.
-- Trước migration này username chỉ được check ở tầng application (isUserNameAlreadyInUse),
-- vốn không atomic → 2 request song song cùng username có thể tạo bản ghi trùng.
--
-- Migration này khớp với check hiện tại của app (so sánh phân biệt hoa/thường:
-- "u.username = :username"), nên dùng unique constraint case-sensitive.
--
-- LƯU Ý: nếu DB đang tồn tại username trùng, migration sẽ FAIL.
-- Chạy query bên dưới để phát hiện trùng trước khi migrate:
--   select username, count(*) from users group by username having count(*) > 1;

alter table users
    add constraint uk_users_username unique (username);
