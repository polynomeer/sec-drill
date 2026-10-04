BEGIN;

CREATE TABLE users (
  id uuid PRIMARY KEY,
  pseudonym text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

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
  UNIQUE (session_id, generation)
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
  UNIQUE (submission_id,kind,revision)
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

COMMIT;
