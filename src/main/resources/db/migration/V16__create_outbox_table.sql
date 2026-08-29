CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50),
    aggregate_id  BIGINT,
    payload       JSONB NOT NULL,                 -- {to, templateName, variables{...}}
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING|QUEUED|SENT|FAILED
    retry_count   INT NOT NULL DEFAULT 0,
    max_retries   INT NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending ON outbox (next_retry_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_status_queued ON outbox (created_at) WHERE status = 'QUEUED';
CREATE INDEX idx_outbox_status_sent_created ON outbox (status, created_at);