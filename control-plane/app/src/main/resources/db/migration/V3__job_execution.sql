-- V3: job execution bookkeeping and outbox delivery tracking (T05, ADR 0003/0004).
-- Mirrors the async section of SecDrill-docs/contracts/schema.sql.

ALTER TABLE jobs ADD COLUMN last_error text CHECK (last_error IN ('DISPATCH_TIMEOUT','LEASE_EXPIRED','PLATFORM_ERROR','CONTENT_INVALID'));
ALTER TABLE jobs ADD COLUMN result_digest text CHECK (result_digest ~ '^[a-f0-9]{64}$');
ALTER TABLE jobs ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
-- A lease and its fencing token exist only while a worker owns the job.
ALTER TABLE jobs ADD CONSTRAINT jobs_lease_consistency
  CHECK ((state IN ('LEASED','RUNNING')) = (lease_until IS NOT NULL AND worker_id IS NOT NULL));
CREATE INDEX jobs_dispatched ON jobs(dispatched_at, id) WHERE state = 'DISPATCHED';
CREATE INDEX jobs_leased ON jobs(lease_until, id) WHERE state IN ('LEASED','RUNNING');

ALTER TABLE outbox_events ADD COLUMN publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0);
ALTER TABLE outbox_events ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE outbox_events ADD COLUMN last_publish_error text CHECK (length(last_publish_error) <= 500);
CREATE INDEX outbox_due ON outbox_events(next_attempt_at, id) WHERE published_at IS NULL;

-- Replays must return the first response byte for byte (15); jsonb would reorder and reformat it.
ALTER TABLE idempotency_records ALTER COLUMN response_body TYPE text USING response_body::text;
