-- ============================================ --
--  V14: Composite index cho keyset pagination  --
--  Endpoint: GET /api/v1/jobs/manage           --
--  Query keyset:                                --
--    WHERE company_id = ? AND is_deleted = false --
--      AND (created_at, id) < (?, ?)             --
--    ORDER BY created_at DESC, id DESC LIMIT ?   --
-- ============================================ --

CREATE INDEX IF NOT EXISTS idx_jobs_company_created_id
    ON jobs (company_id, created_at DESC, id DESC);