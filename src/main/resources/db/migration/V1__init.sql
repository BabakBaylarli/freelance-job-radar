-- Every job we have ever seen, matched or not (so /stats can show seen vs matched and so we never re-process a job).
CREATE TABLE jobs (
    id               BIGSERIAL PRIMARY KEY,
    source           VARCHAR(40)  NOT NULL,
    external_id      VARCHAR(255) NOT NULL,
    url              TEXT         NOT NULL,
    title            VARCHAR(500) NOT NULL,
    company          VARCHAR(255) NOT NULL DEFAULT '',
    tags             TEXT[]       NOT NULL DEFAULT '{}',
    description      TEXT         NOT NULL DEFAULT '',
    salary           VARCHAR(255),
    posted_at        TIMESTAMPTZ,
    first_seen_at    TIMESTAMPTZ  NOT NULL,
    score            INT          NOT NULL,
    matched_keywords TEXT[]       NOT NULL DEFAULT '{}',
    excluded_by      VARCHAR(255),
    matched          BOOLEAN      NOT NULL,
    -- NONE: not a match | PENDING: waiting to be sent | DEFERRED: waits for the next digest | SENT_*: delivered
    delivery         VARCHAR(20)  NOT NULL,
    -- what the owner did with it in Telegram
    user_status      VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    notified_at      TIMESTAMPTZ,
    CONSTRAINT uq_jobs_source_external UNIQUE (source, external_id),
    CONSTRAINT uq_jobs_url UNIQUE (url)
);

CREATE INDEX idx_jobs_delivery ON jobs (delivery, user_status);
CREATE INDEX idx_jobs_first_seen ON jobs (first_seen_at);
CREATE INDEX idx_jobs_notified ON jobs (notified_at);

-- Remembers when each source was last polled so a restart never breaks a source's polling rules.
CREATE TABLE source_state (
    source          VARCHAR(40) PRIMARY KEY,
    last_polled_at  TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_status     VARCHAR(20),
    last_error      TEXT,
    last_fetched    INT NOT NULL DEFAULT 0,
    last_new        INT NOT NULL DEFAULT 0
);

-- Single-owner bot: exactly one row.
CREATE TABLE bot_state (
    id             SMALLINT PRIMARY KEY CHECK (id = 1),
    paused         BOOLEAN NOT NULL DEFAULT FALSE,
    last_digest_at TIMESTAMPTZ
);
INSERT INTO bot_state (id) VALUES (1);
