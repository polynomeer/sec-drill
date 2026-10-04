BEGIN;

CREATE TABLE users (
  id uuid PRIMARY KEY,
  pseudonym text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Idempotency-Key replay store (15). submissions.client_request_id holds the same key value.
CREATE TABLE idempotency_records (
  owner_id uuid NOT NULL REFERENCES users(id),
  route text NOT NULL,
  idempotency_key uuid NOT NULL,
  request_digest text NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
  response_status integer NOT NULL CHECK (response_status BETWEEN 100 AND 599),
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (owner_id, route, idempotency_key),
  CHECK (expires_at > created_at)
);
CREATE INDEX idempotency_records_expiry ON idempotency_records(expires_at);

CREATE TABLE scenarios (
  id uuid PRIMARY KEY,
  slug text NOT NULL UNIQUE,
  title text NOT NULL
);

CREATE TABLE scenario_versions (
  id uuid PRIMARY KEY,
  scenario_id uuid NOT NULL REFERENCES scenarios(id),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL CHECK (status IN ('DRAFT','VALIDATED','PUBLISHED','QUARANTINED')),
  content_digest text NOT NULL CHECK (content_digest ~ '^[a-f0-9]{64}$'),
  oracle_digest text NOT NULL CHECK (oracle_digest ~ '^[a-f0-9]{64}$'),
  oracle_key text NOT NULL,
  rubric_version text NOT NULL,
  engine_version text NOT NULL,
  randomization_version text NOT NULL,
  public_manifest jsonb NOT NULL,
  published_at timestamptz,
  UNIQUE (scenario_id, version_no)
);

CREATE TABLE challenges (
  id uuid PRIMARY KEY,
  version_id uuid NOT NULL REFERENCES scenario_versions(id),
  challenge_key text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('FLAG','OBJECTIVE')),
  public_spec jsonb NOT NULL,
  UNIQUE (version_id, challenge_key)
);

CREATE TABLE sessions (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES users(id),
  scenario_version_id uuid NOT NULL REFERENCES scenario_versions(id),
  parent_session_id uuid REFERENCES sessions(id),
  mode text NOT NULL CHECK (mode IN ('CTF','WARGAME','PURPLE','PATCH','DETECTION','INVESTIGATE')),
  status text NOT NULL CHECK (status IN ('CREATED','ACTIVE','SUBMITTED','EVALUATING','COMPLETED','CANCELLED','EXPIRED','EVALUATION_FAILED')),
  phase text NOT NULL CHECK (phase IN ('ANALYZE','ATTACK','OBSERVE','DETECT','CONTAIN','PATCH','VERIFY','POSTMORTEM')),
  seed bytea NOT NULL,
  rubric_version text NOT NULL,
  engine_version text NOT NULL,
  randomization_version text NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, owner_id),
  FOREIGN KEY (parent_session_id, owner_id) REFERENCES sessions(id, owner_id),
  CHECK (parent_session_id IS DISTINCT FROM id)
);
CREATE INDEX sessions_owner_created ON sessions(owner_id, created_at DESC, id);

CREATE TABLE artifacts (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES sessions(id),
  object_key text NOT NULL UNIQUE,
  digest text NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
  byte_size bigint NOT NULL CHECK (byte_size >= 0),
  media_type text NOT NULL,
  sensitivity text NOT NULL CHECK (sensitivity IN ('LEARNER','PRIVATE_ORACLE','RAW_LOG','PUBLIC_SUMMARY')),
  expires_at timestamptz,
  deleted_at timestamptz,
  UNIQUE (id, session_id)
);

CREATE TABLE labs (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL,
  owner_id uuid NOT NULL,
  generation integer NOT NULL CHECK (generation > 0),
  state text NOT NULL CHECK (state IN ('REQUESTED','PROVISIONING','READY','TERMINATING','TERMINATED','FAILED','CLEANUP_FAILED')),
  runtime_ref text,
  expires_at timestamptz NOT NULL,
  cleanup_confirmed_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (session_id, owner_id) REFERENCES sessions(id, owner_id),
  UNIQUE (session_id, generation),
  UNIQUE (id, session_id),
  -- TERMINATED means runtime, network and disk reclaim was confirmed (13).
  -- A FAILED lab with no residual resources also records cleanup_confirmed_at.
  CHECK (state <> 'TERMINATED' OR cleanup_confirmed_at IS NOT NULL)
);
CREATE UNIQUE INDEX labs_owner_active ON labs(owner_id)
  WHERE cleanup_confirmed_at IS NULL;
CREATE INDEX labs_expiry ON labs(expires_at) WHERE cleanup_confirmed_at IS NULL;

CREATE TABLE submissions (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES sessions(id),
  kind text NOT NULL CHECK (kind IN ('FLAG','OBJECTIVE','PATCH','DETECTION','POSTMORTEM')),
  artifact_id uuid,
  client_request_id uuid NOT NULL,
  request_digest text NOT NULL CHECK (request_digest ~ '^[a-f0-9]{64}$'),
  status text NOT NULL CHECK (status IN ('ACCEPTED','EVALUATING','EVALUATED','EVALUATION_FAILED')),
  safe_metadata jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (artifact_id, session_id) REFERENCES artifacts(id, session_id),
  UNIQUE (session_id, client_request_id),
  UNIQUE (id,session_id)
);
CREATE INDEX submissions_session_created ON submissions(session_id,created_at,id);

CREATE TABLE jobs (
  id uuid PRIMARY KEY,
  submission_id uuid REFERENCES submissions(id),
  lab_id uuid,
  session_id uuid NOT NULL REFERENCES sessions(id),
  kind text NOT NULL CHECK (kind IN ('PROVISION','GRADE','REPORT','CLEANUP','EXPORT')),
  revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
  state text NOT NULL CHECK (state IN ('PENDING','DISPATCHED','LEASED','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED','CANCELLED')),
  attempt integer NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
  fencing_token bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
  worker_id text,
  lease_until timestamptz,
  dispatched_at timestamptz,
  due_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (submission_id,session_id) REFERENCES submissions(id,session_id),
  FOREIGN KEY (lab_id,session_id) REFERENCES labs(id,session_id),
  UNIQUE (submission_id,kind,revision),
  UNIQUE (lab_id,kind,revision),
  CHECK (CASE kind
    WHEN 'GRADE' THEN submission_id IS NOT NULL AND lab_id IS NULL
    WHEN 'PROVISION' THEN lab_id IS NOT NULL AND submission_id IS NULL
    WHEN 'CLEANUP' THEN lab_id IS NOT NULL AND submission_id IS NULL
    ELSE submission_id IS NULL AND lab_id IS NULL
  END)
);
CREATE INDEX jobs_due ON jobs(due_at,id) WHERE state IN ('PENDING','RETRY_WAIT');

CREATE TABLE evaluations (
  id uuid PRIMARY KEY,
  submission_id uuid NOT NULL REFERENCES submissions(id),
  revision integer NOT NULL CHECK (revision > 0),
  policy_version text NOT NULL,
  verdict text NOT NULL CHECK (verdict IN ('PASS','FAIL','SYSTEM_ERROR')),
  patch_gate text CHECK (patch_gate IN ('VERIFIED','NOT_VERIFIED','INCONCLUSIVE')),
  dimensions jsonb NOT NULL,
  gates jsonb NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  supersedes_id uuid REFERENCES evaluations(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (submission_id,revision)
);
CREATE UNIQUE INDEX evaluations_one_active ON evaluations(submission_id) WHERE is_active;

CREATE TABLE ledger_heads (
  session_id uuid PRIMARY KEY REFERENCES sessions(id),
  last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  last_hash text NOT NULL CHECK (last_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE evidence (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES sessions(id),
  seq bigint NOT NULL CHECK (seq > 0),
  event_type text NOT NULL,
  source text NOT NULL CHECK (source IN ('CONTROL','VERIFIER','SUPERVISOR','COLLECTOR','USER','SIMULATOR')),
  trust_level text NOT NULL CHECK (trust_level IN ('SERVER_VERIFIED','OBSERVED','USER_REPORTED','SIMULATED')),
  schema_version integer NOT NULL CHECK (schema_version > 0),
  occurred_at timestamptz NOT NULL,
  ingested_at timestamptz NOT NULL DEFAULT now(),
  artifact_id uuid,
  payload_digest text NOT NULL CHECK (payload_digest ~ '^[a-f0-9]{64}$'),
  previous_hash text NOT NULL CHECK (previous_hash ~ '^[a-f0-9]{64}$'),
  hash text NOT NULL CHECK (hash ~ '^[a-f0-9]{64}$'),
  safe_payload jsonb NOT NULL DEFAULT '{}',
  FOREIGN KEY (artifact_id,session_id) REFERENCES artifacts(id,session_id),
  UNIQUE (session_id,seq)
);

CREATE FUNCTION reject_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Evidence is append-only; use an approved privacy deletion role';
END;
$$;
CREATE TRIGGER evidence_no_update BEFORE UPDATE ON evidence
  FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER evidence_no_delete BEFORE DELETE ON evidence
  FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  event_type text NOT NULL,
  envelope jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
CREATE INDEX outbox_unpublished ON outbox_events(created_at,id) WHERE published_at IS NULL;

CREATE TABLE consumer_inbox (
  consumer text NOT NULL,
  event_id uuid NOT NULL,
  consumed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer,event_id)
);

-- Identity, login sessions, operator tokens and audit (T02).
-- External identity link. Only issuer and subject are kept; no email or profile data (NFR-03).
CREATE TABLE user_identities (
  issuer text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject text NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
  user_id uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (issuer, subject)
);
CREATE INDEX user_identities_user ON user_identities(user_id);

-- One platform login (a refresh-token family). Secrets are stored only as SHA-256 hashes.
CREATE TABLE auth_sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  csrf_hash text NOT NULL CHECK (csrf_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL,
  revoked_at timestamptz,
  revoke_reason text CHECK (revoke_reason IN ('LOGOUT','REFRESH_REUSE','OPERATOR')),
  CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX auth_sessions_user_live ON auth_sessions(user_id) WHERE revoked_at IS NULL;

CREATE TABLE auth_tokens (
  token_hash text PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  auth_session_id uuid NOT NULL REFERENCES auth_sessions(id),
  kind text NOT NULL CHECK (kind IN ('ACCESS','REFRESH')),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  superseded_at timestamptz,
  CHECK (expires_at > issued_at)
);
CREATE INDEX auth_tokens_session ON auth_tokens(auth_session_id);
-- Rotation keeps exactly one live refresh token per login; presenting a superseded one is reuse.
CREATE UNIQUE INDEX auth_tokens_one_live_refresh ON auth_tokens(auth_session_id)
  WHERE kind = 'REFRESH' AND superseded_at IS NULL;

-- Operator bearer tokens are separate from learner sessions (15, 19) and short-lived.
CREATE TABLE operator_tokens (
  token_hash text PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  operator_id uuid NOT NULL,
  role text NOT NULL CHECK (role IN ('OPERATOR','SECURITY_ADMIN')),
  purpose text NOT NULL CHECK (length(purpose) BETWEEN 3 AND 500),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '12 hours')
);

CREATE TABLE audit_events (
  id uuid PRIMARY KEY,
  actor_type text NOT NULL CHECK (actor_type IN ('OPERATOR','SYSTEM')),
  actor_id uuid NOT NULL,
  purpose text NOT NULL,
  action text NOT NULL CHECK (length(action) BETWEEN 1 AND 300),
  occurred_at timestamptz NOT NULL
);
CREATE INDEX audit_events_actor ON audit_events(actor_id, occurred_at);

CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Audit events are append-only';
END;
$$;
CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

-- Job execution bookkeeping and outbox delivery tracking (T05).
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

-- Evidence trust rules, deletion contract and runtime role (T09).
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

COMMIT;
