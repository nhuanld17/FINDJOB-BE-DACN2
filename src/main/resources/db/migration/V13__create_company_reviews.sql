-- V13: Tạo bảng đánh giá công ty (company_reviews)
-- Mục đích: Cho phép employee đánh giá + chấm điểm công ty
-- Mỗi employee chỉ được đánh giá 1 lần / 1 công ty (UNIQUE constraint)

create table company_reviews (
    id                bigserial       primary key,
    company_id        bigint          not null references companies(id) on delete cascade,
    employee_id       bigint          not null references employees(id) on delete cascade,

    -- Nội dung đánh giá
    rating            integer         not null check (rating >= 1 and rating <= 5),
    title             varchar(255),
    content           text            not null,
    pros              text,
    cons              text,

    created_at        timestamptz     not null default now(),
    updated_at        timestamptz     not null default now(),

    -- Mỗi employee chỉ đánh giá 1 lần / 1 công ty
    unique (company_id, employee_id)
);

create index idx_company_reviews_company on company_reviews(company_id);
create index idx_company_reviews_employee on company_reviews(employee_id);

-- Cache rating trên bảng companies (tránh tính toán lại mỗi lần)
alter table companies
    add column average_rating double precision not null default 0.0,
    add column total_reviews  integer         not null default 0;

comment on column companies.average_rating is 'Điểm đánh giá trung bình (cache)';
comment on column companies.total_reviews  is 'Tổng số lượt đánh giá (cache)';
