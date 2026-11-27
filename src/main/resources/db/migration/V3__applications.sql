-- Human-approved applications: every proposal the owner drafted, edited, sent or copied (never sent automatically).
CREATE TABLE applications (
    id               BIGSERIAL PRIMARY KEY,
    job_id           BIGINT       NOT NULL REFERENCES jobs (id),
    -- which route is used: freelancer | email | telegram | copy (copy-and-open: no automated submission)
    platform         VARCHAR(30)  NOT NULL,
    -- DRAFT -> (EDITING) -> (CONFIRMING) -> SENT | FAILED ; COPIED (copy-and-open) ; CANCELLED
    status           VARCHAR(20)  NOT NULL,
    text             TEXT         NOT NULL,
    bid_amount       NUMERIC(14,2),
    bid_currency     VARCHAR(8),
    bid_days         INT,
    -- who it goes to, shown to the owner before sending: an e-mail address or a Telegram @username
    recipient        VARCHAR(255),
    -- Telegram message id of the draft, so that replying "ok" to that message approves exactly this application
    draft_message_id BIGINT,
    response_id      VARCHAR(255),
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    sent_at          TIMESTAMPTZ
);

-- A job is never sent twice, enforced by the database.
CREATE UNIQUE INDEX uq_applications_job_sent ON applications (job_id) WHERE status = 'SENT';
CREATE INDEX idx_applications_job ON applications (job_id);
CREATE INDEX idx_applications_draft_message ON applications (draft_message_id);
CREATE INDEX idx_applications_sent_at ON applications (sent_at) WHERE status = 'SENT';
