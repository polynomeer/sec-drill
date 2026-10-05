-- V6: Lab desired state, TTLs, termination and cleanup receipts; runner workload credentials (T04/T06, ADR 0007).
-- Mirrors the lab section of SecDrill-docs/contracts/schema.sql.

-- Desired state is what the Control Plane wants; state is what was observed (13, 16).
ALTER TABLE labs ADD COLUMN desired_state text NOT NULL DEFAULT 'RUNNING' CHECK (desired_state IN ('RUNNING','TERMINATED'));
ALTER TABLE labs ADD COLUMN idle_expires_at timestamptz;
ALTER TABLE labs ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE labs ADD COLUMN ready_at timestamptz;
ALTER TABLE labs ADD COLUMN runner_id text CHECK (runner_id ~ '^[a-z0-9-]{3,64}$');
-- Upstream address the runner reported for the Lab Gateway. Internal only; never shown to learners.
ALTER TABLE labs ADD COLUMN endpoint text CHECK (length(endpoint) <= 300);
ALTER TABLE labs ADD COLUMN terminate_reason text
  CHECK (terminate_reason IN ('USER_STOP','IDLE_TTL','HARD_TTL','OPERATOR','PROVISION_FAILED','RUNTIME_LOST','ORPHAN'));
ALTER TABLE labs ADD COLUMN terminate_requested_at timestamptz;
ALTER TABLE labs ADD COLUMN cleanup_receipt jsonb;
ALTER TABLE labs ADD CONSTRAINT labs_termination_requested
  CHECK ((desired_state = 'TERMINATED') = (terminate_reason IS NOT NULL AND terminate_requested_at IS NOT NULL));
ALTER TABLE labs ADD CONSTRAINT labs_ready_wanted
  CHECK (state <> 'READY' OR (desired_state = 'RUNNING' AND ready_at IS NOT NULL AND runtime_ref IS NOT NULL));
ALTER TABLE labs ADD CONSTRAINT labs_terminated_receipt CHECK (state <> 'TERMINATED' OR cleanup_receipt IS NOT NULL);
CREATE INDEX labs_live_expiry ON labs(idle_expires_at, expires_at) WHERE desired_state = 'RUNNING';

-- Runner and gateway workload identities (11, 19). Short-lived bearer credentials stored as hashes; mTLS is later (D-17).
CREATE TABLE runner_credentials (
  token_hash text PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  runner_id text NOT NULL CHECK (runner_id ~ '^[a-z0-9-]{3,64}$'),
  kind text NOT NULL CHECK (kind IN ('AGENT','GATEWAY')),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '24 hours')
);
CREATE INDEX runner_credentials_runner ON runner_credentials(runner_id);

GRANT SELECT, INSERT, UPDATE ON runner_credentials TO control_app;
