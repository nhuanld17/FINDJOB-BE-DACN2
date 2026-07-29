-- ============================================ --
--  V8: Alter years_of_experience to integer    --
-- ============================================ --

-- Đổi kiểu dữ liệu từ varchar(30) sang integer
-- Các giá trị cũ không phải số sẽ bị set thành NULL
ALTER TABLE jobs
    ALTER COLUMN years_of_experience TYPE integer
    USING CASE
        WHEN years_of_experience ~ '^\d+$' THEN years_of_experience::integer
        ELSE NULL
    END;
