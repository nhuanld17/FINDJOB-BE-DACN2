-- Seed E2E test accounts for FE Playwright/Antigravity.
-- Idempotent inserts: safe to run multiple times.
--
-- Accounts (password plaintext: 12345678):
-- 1) login_active_01@test.com   (active=true, deleted=false)
-- 2) login_inactive_01@test.com (active=false, deleted=false)
-- 3) login_banned_01@test.com   (active=true, deleted=true)
--
-- NOTE:
-- - Password is stored as BCrypt hash of "12345678".
-- - Roles enum values are normalized to USER/ADMIN by V2 migration.

-- Ensure roles exist (idempotent)
insert into roles (name)
select 'USER'
where not exists (select 1 from roles where name = 'USER');

insert into roles (name)
select 'ADMIN'
where not exists (select 1 from roles where name = 'ADMIN');

-- BCrypt hash for plaintext "12345678"
-- (Any valid bcrypt hash works; this is deterministic for seeding.)
-- Generated with BCrypt 10 rounds.
-- Ref: Spring Security BCryptPasswordEncoder
do $$
declare
  bcrypt_12345678 text := '$2a$12$s5qzOMHFPWuiFq7mv9sqVu4TmC9mxiqLji9.HpHLpO.wu9uWVsHwu';
begin
  -- Users
  insert into users (username, email, password, full_name, is_active, is_deleted, created_at, updated_at)
  select 'login_active_01', 'login_active_01@test.com', bcrypt_12345678, 'Login Active 01', true, false, now(), now()
  where not exists (select 1 from users where email = 'login_active_01@test.com');

  insert into users (username, email, password, full_name, is_active, is_deleted, created_at, updated_at)
  select 'login_inactive_01', 'login_inactive_01@test.com', bcrypt_12345678, 'Login Inactive 01', false, false, now(), now()
  where not exists (select 1 from users where email = 'login_inactive_01@test.com');

  insert into users (username, email, password, full_name, is_active, is_deleted, created_at, updated_at)
  select 'login_banned_01', 'login_banned_01@test.com', bcrypt_12345678, 'Login Banned 01', true, true, now(), now()
  where not exists (select 1 from users where email = 'login_banned_01@test.com');

  -- User role mapping (USER)
  insert into user_role (user_id, role_id)
  select u.id, r.id
  from users u
  join roles r on r.name = 'USER'
  where u.email in ('login_active_01@test.com','login_inactive_01@test.com','login_banned_01@test.com')
    and not exists (
      select 1 from user_role ur
      where ur.user_id = u.id and ur.role_id = r.id
    );
end $$;

