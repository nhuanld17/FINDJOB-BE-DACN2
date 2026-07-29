-- ============================================= --
--  V11: Seed missing Employee profiles        --
--                                             --
--  Fix: User mới login Google OIDC (web flow) --
--  trước V11 không được tạo Employee profile. --
--  Migration này tạo Employee cho các user     --
--  có role USER nhưng chưa có employee record. --
-- ============================================= --

insert into employees (user_id, created_at, updated_at)
select u.id, now(), now()
from users u
inner join user_role ur on ur.user_id = u.id
inner join roles r on r.id = ur.role_id and r.name = 'USER'
left join employees e on e.user_id = u.id
where e.id is null
on conflict (user_id) do nothing;
