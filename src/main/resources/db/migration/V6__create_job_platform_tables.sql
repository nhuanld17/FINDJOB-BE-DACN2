-- ============================================ --
--  V6: Create Job Platform Tables             --
--  - Seed ROLE_COMPANY                         --
--  - companies, employees, certificates        --
--  - jobs, categories, job_categories          --
--  - applications, saved_jobs,                 --
--    followed_companies, notifications         --
-- ============================================ --

-- =============================== --
--         SEED ROLE_COMPANY       --
-- =============================== --

insert into roles (name)
select 'COMPANY'
where not exists (select 1 from roles where name = 'COMPANY');

-- =============================== --
--             COMPANIES            --
-- =============================== --
create table companies (
    id                bigserial       primary key,
    owner_id          bigint          not null unique references users(id),
    -- ^ Chủ sở hữu công ty (user có role COMPANY)

    -- Thông tin công ty
    name              varchar(255)    not null,
    slug              varchar(255)    not null unique,
    description       text,
    logo_url          varchar(500),
    cover_url         varchar(500),
    website           varchar(255),
    company_size      varchar(50),
    industry          varchar(100),

    -- Địa chỉ
    city              varchar(100)    not null,
    address           varchar(500),

    -- Liên hệ
    email             varchar(255),
    phone             varchar(20),

    -- Social
    facebook_url      varchar(255),
    linkedin_url      varchar(255),

    -- Thông tin người đại diện
    contact_position  varchar(255),

    created_at        timestamptz     not null default now(),
    updated_at        timestamptz     not null default now(),
    is_deleted        boolean         not null default false
);

create index idx_companies_slug on companies(slug);
create index idx_companies_city on companies(city);
create index idx_companies_industry on companies(industry);

-- =============================== --
--            EMPLOYEES             --
-- =============================== --
create table employees (
    id                bigserial       primary key,
    user_id           bigint          not null unique references users(id),

    -- Thông tin cơ bản
    phone             varchar(20),
    date_of_birth     date,
    gender            varchar(10),

    -- Địa chỉ
    city              varchar(100),
    address           varchar(500),

    -- CV & Portfolio
    cv_url            varchar(500),
    github_url        varchar(255),
    linkedin_url      varchar(255),
    portfolio_url     varchar(255),

    -- Trạng thái tìm việc
    is_public         boolean         default true,
    is_open_to_work   boolean         default false,
    title             varchar(255),
    bio               text,

    -- JSONB data
    skills            jsonb           default '[]'::jsonb,
    experiences       jsonb           default '[]'::jsonb,
    education         jsonb           default '[]'::jsonb,

    created_at        timestamptz     not null default now(),
    updated_at        timestamptz     not null default now()
);

create index idx_employee_skills on employees using gin(skills);
create index idx_employee_experiences on employees using gin(experiences);
create index idx_employee_title on employees(title);
create index idx_employee_city on employees(city);
create index idx_employee_open_to_work on employees(is_open_to_work);

-- =============================== --
--          CERTIFICATES            --
-- =============================== --
create table certificates (
    id                bigserial       primary key,
    employee_id       bigint          not null references employees(id) on delete cascade,
    name              varchar(255)    not null,
    issuer            varchar(255),
    issue_date        date,
    expiry_date       date,
    credential_url    varchar(500),
    created_at        timestamptz     not null default now()
);

create index idx_certificates_employee on certificates(employee_id);

-- =============================== --
--               JOBS               --
-- =============================== --
create table jobs (
    id                bigserial       primary key,
    company_id        bigint          not null references companies(id),
    created_by        bigint          not null references users(id),

    -- Core
    title             varchar(255)    not null,
    slug              varchar(255)    not null,
    description       text            not null,
    requirements      text            not null,
    benefits          text,

    -- Lương
    salary_min        numeric(12, 2),
    salary_max        numeric(12, 2),
    salary_currency   varchar(10)     default 'VND',

    -- Phân loại
    years_of_experience varchar(30),
    seniority         varchar(30)     not null,
    job_type          varchar(30)     not null,
    location          varchar(255),
    city              varchar(100)    not null,

    -- Skills yêu cầu
    skills_required   jsonb           default '[]'::jsonb,

    -- Thời gian
    expiry_date       date            not null,

    -- Stats
    apply_count       integer         default 0,

    -- Status
    status            varchar(20)     not null default 'ACTIVE',

    created_at        timestamptz     not null default now(),
    updated_at        timestamptz     not null default now(),
    is_deleted        boolean         not null default false
);

create index idx_jobs_status on jobs(status);
create index idx_jobs_city on jobs(city);
create index idx_jobs_seniority on jobs(seniority);
create index idx_jobs_job_type on jobs(job_type);
create index idx_jobs_expiry on jobs(expiry_date);
create index idx_jobs_company on jobs(company_id);
create index idx_jobs_created_by on jobs(created_by);
create index idx_jobs_skills on jobs using gin(skills_required);

-- Full-text search index
-- Dùng 'english' vì PostgreSQL không có sẵn Vietnamese FTS config.
-- JD công ty IT thường viết lẫn Anh-Việt nên 'english' vẫn hoạt động tốt.
create index idx_jobs_search on jobs using gin(
    to_tsvector('english',
        coalesce(title, '') || ' ' ||
        coalesce(description, '') || ' ' ||
        coalesce(requirements, '')
    )
);

-- Đảm bảo slug là duy nhất trong cùng một công ty
create unique index idx_jobs_company_slug on jobs(company_id, slug);

-- =============================== --
--           CATEGORIES             --
-- =============================== --
create table categories (
    id                bigserial       primary key,
    name              varchar(100)    not null unique,
    slug              varchar(100)    not null unique,
    description       text,
    created_at        timestamptz     not null default now()
);

create table job_categories (
    job_id            bigint          not null references jobs(id) on delete cascade,
    category_id       bigint          not null references categories(id) on delete cascade,
    primary key (job_id, category_id)
);

-- =============================== --
--           APPLICATIONS           --
-- =============================== --
create table applications (
    id                bigserial       primary key,
    job_id            bigint          not null references jobs(id),
    employee_id       bigint          not null references employees(id),

    -- Nội dung
    cover_letter      text,
    cv_url            varchar(500),

    -- Trạng thái
    status            varchar(30)     not null default 'PENDING',
    recruiter_note    text,
    rejected_reason   text,

    -- Timeline
    applied_at        timestamptz     not null default now(),
    reviewed_at       timestamptz,
    responded_at      timestamptz,

    unique (job_id, employee_id)
);

create index idx_applications_job on applications(job_id);
create index idx_applications_employee on applications(employee_id);
create index idx_applications_status on applications(status);

-- =============================== --
--            SAVED JOBS            --
-- =============================== --
create table saved_jobs (
    employee_id       bigint          not null references employees(id) on delete cascade,
    job_id            bigint          not null references jobs(id) on delete cascade,
    saved_at          timestamptz     not null default now(),
    note              text,
    primary key (employee_id, job_id)
);

-- =============================== --
--        FOLLOWED COMPANIES        --
-- =============================== --
create table followed_companies (
    employee_id       bigint          not null references employees(id) on delete cascade,
    company_id        bigint          not null references companies(id) on delete cascade,
    followed_at       timestamptz     not null default now(),
    primary key (employee_id, company_id)
);

-- =============================== --
--          NOTIFICATIONS           --
-- =============================== --
create table notifications (
    id                bigserial       primary key,
    user_id           bigint          not null references users(id) on delete cascade,
    type              varchar(50)     not null,
    title             varchar(255)    not null,
    content           text            not null,
    link              varchar(500),
    is_read           boolean         default false,
    created_at        timestamptz     not null default now()
);

create index idx_notifications_user on notifications(user_id, is_read, created_at desc);
