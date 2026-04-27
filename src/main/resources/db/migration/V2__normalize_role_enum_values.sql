-- Normalize role enum values to match RoleEnum (USER, ADMIN).
-- Some environments seeded roles as ROLE_USER / ROLE_ADMIN.
-- This migration converts them to USER / ADMIN, ensuring Hibernate enum mapping works.

-- Update existing seeded values
update roles set name = 'USER'  where name = 'ROLE_USER';
update roles set name = 'ADMIN' where name = 'ROLE_ADMIN';

-- Ensure required roles exist (idempotent)
insert into roles (name)
select 'USER'
where not exists (select 1 from roles where name = 'USER');

insert into roles (name)
select 'ADMIN'
where not exists (select 1 from roles where name = 'ADMIN');

