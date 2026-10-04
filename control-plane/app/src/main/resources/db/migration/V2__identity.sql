-- V2: identity, platform login sessions, operator tokens and audit (T02, ADR 0002).
-- Mirrors the identity section of SecDrill-docs/contracts/schema.sql.

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
