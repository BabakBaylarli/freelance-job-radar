-- Multi-market support: where a job comes from, what kind of job it is, its numeric budget,
-- and cross-source de-duplication (same posting seen on several platforms).
ALTER TABLE jobs
    ADD COLUMN market     VARCHAR(20)   NOT NULL DEFAULT 'INTERNATIONAL',
    ADD COLUMN job_type   VARCHAR(30)   NOT NULL DEFAULT 'REMOTE_JOB',
    ADD COLUMN budget_min NUMERIC(14,2),
    ADD COLUMN budget_max NUMERIC(14,2),
    ADD COLUMN currency   VARCHAR(8),
    ADD COLUMN hourly     BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN dedup_key  TEXT,
    -- other places the same job was seen, as "source|url"
    ADD COLUMN also_on    TEXT[]        NOT NULL DEFAULT '{}';

CREATE INDEX idx_jobs_dedup_key ON jobs (dedup_key, first_seen_at) WHERE dedup_key IS NOT NULL;
CREATE INDEX idx_jobs_market ON jobs (market, delivery, user_status);

-- Markets the owner switched off with /markets
ALTER TABLE bot_state ADD COLUMN disabled_markets TEXT[] NOT NULL DEFAULT '{}';
