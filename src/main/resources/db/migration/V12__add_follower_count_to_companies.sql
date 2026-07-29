-- V12: Thêm cột follower_count vào bảng companies (đếm cache)
-- Mục đích: Tránh phải COUNT(*) trên followed_companies mỗi lần hiển thị danh sách company.

alter table companies
    add column follower_count integer not null default 0;

-- Seed dữ liệu hiện tại: đếm follower thực tế cho các company đã có sẵn
update companies c
set follower_count = (
    select count(*) from followed_companies fc where fc.company_id = c.id
);

comment on column companies.follower_count is 'Số lượng employee đang follow company này (cache counter)';
