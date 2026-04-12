-- =============================== --
--             ROLES               --
-- =============================== --
create table roles(
    id      bigserial       primary key,
    name    varchar(50)     not null unique
);

-- =============================== --
--             USERS               --
-- =============================== --
create table users(
    id          bigserial       primary key ,
    username    varchar(255)    not null ,
    email       varchar(255)    not null unique ,
    password    varchar(255)    not null ,
    full_name   varchar(100),
    is_active   boolean         not null default false,
    is_deleted  boolean         not null default false,
    created_at  timestamptz     not null default now(),
    updated_at  timestamptz     not null default now()
);

-- =============================== --
--           USER_ROLES            --
-- =============================== --
create table user_role(
    user_id     bigint not null references users(id) on delete cascade ,
    role_id     bigint not null references roles(id) on delete cascade ,
    primary key (user_id, role_id)
);

-- =============================== --
--            SEED DATA            --
-- =============================== --
insert into roles (name) values ('ROLE_USER');
insert into roles (name) values ('ROLE_ADMIN');
