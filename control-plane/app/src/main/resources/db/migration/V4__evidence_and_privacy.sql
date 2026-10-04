-- V4: Evidence trust rules and dedupe, deletion contract, least-privilege runtime role (T09, ADR 0005).
-- Mirrors the evidence and privacy section of SecDrill-docs/contracts/schema.sql.

-- An event recorded twice (redelivery) maps to one Evidence row per Session.
ALTER TABLE evidence ADD COLUMN source_event_id uuid;
CREATE UNIQUE INDEX evidence_source_event ON evidence(session_id, source_event_id) WHERE source_event_id IS NOT NULL;

-- Official results and learner claims never share a trust level (10, prompt 05).
ALTER TABLE evidence ADD CONSTRAINT evidence_trust_matches_source CHECK (
  (source = 'USER') = (trust_level = 'USER_REPORTED')
  AND (trust_level <> 'SERVER_VERIFIED' OR source IN ('CONTROL','VERIFIER','SUPERVISOR'))
  AND (trust_level <> 'OBSERVED' OR source IN ('COLLECTOR','SUPERVISOR'))
  AND (trust_level <> 'SIMULATED' OR source IN ('SIMULATOR','SUPERVISOR'))
);

-- Deletion contract (14, 15, 25). The erasure executor itself is not implemented yet (see PRIVACY_ERASURE_REVIEW).
CREATE TABLE deletion_requests (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES users(id),
  scope text NOT NULL CHECK (scope IN ('ACCOUNT','SESSION')),
  session_id uuid,
  status text NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED','COMPLETED')),
  requested_at timestamptz NOT NULL,
  decided_at timestamptz,
  decided_by uuid,
  completed_at timestamptz,
  receipt_digest text CHECK (receipt_digest ~ '^[a-f0-9]{64}$'),
  FOREIGN KEY (session_id, owner_id) REFERENCES sessions(id, owner_id),
  CHECK ((scope = 'SESSION') = (session_id IS NOT NULL)),
  CHECK ((status = 'REQUESTED') = (decided_at IS NULL AND decided_by IS NULL)),
  CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL AND receipt_digest IS NOT NULL))
);
CREATE INDEX deletion_requests_owner ON deletion_requests(owner_id, requested_at);

-- Re-applied after a backup restore so erased subjects stay erased (14, 25).
CREATE TABLE deletion_tombstones (
  subject_type text NOT NULL CHECK (subject_type IN ('USER','SESSION','ARTIFACT')),
  subject_id uuid NOT NULL,
  deletion_request_id uuid NOT NULL REFERENCES deletion_requests(id),
  applied_at timestamptz NOT NULL,
  PRIMARY KEY (subject_type, subject_id)
);

-- Least-privilege role for the running application (14). Evidence, audit and tombstones are append-only at the
-- privilege level as well as by trigger; the runtime login role is granted this role at deployment (T14).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'control_app') THEN
    CREATE ROLE control_app NOLOGIN;
  END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO control_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON users, user_identities, auth_sessions, auth_tokens, operator_tokens,
  idempotency_records, scenarios, scenario_versions, challenges, sessions, artifacts, labs, submissions, jobs,
  evaluations, ledger_heads, outbox_events, consumer_inbox, deletion_requests TO control_app;
GRANT SELECT, INSERT ON evidence, audit_events, deletion_tombstones TO control_app;
REVOKE DELETE ON evaluations, ledger_heads FROM control_app;
