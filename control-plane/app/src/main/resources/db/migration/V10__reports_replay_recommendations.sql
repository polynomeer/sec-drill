-- V10: report revisions, IR replay checkpoints and recommendation provenance (T12, ADR 0012).
-- Mirrors the T12 section of SecDrill-docs/contracts/schema.sql.

-- A report revision is fixed once written: a re-grade adds a new revision, older ones stay readable (10).
CREATE TABLE reports (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES sessions(id),
  revision integer NOT NULL CHECK (revision > 0),
  policy_version text NOT NULL,
  evaluation_refs jsonb NOT NULL CHECK (jsonb_typeof(evaluation_refs) = 'array'),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (session_id, revision)
);

-- Reducer state every 3 ticks (30 simulated seconds) for seek (22). Seek re-checks state_digest before use.
CREATE TABLE ir_checkpoints (
  session_id uuid NOT NULL REFERENCES sessions(id),
  tick integer NOT NULL CHECK (tick > 0),
  engine_version text NOT NULL,
  state jsonb NOT NULL,
  state_digest text NOT NULL CHECK (state_digest ~ '^[a-f0-9]{64}$'),
  PRIMARY KEY (session_id, tick)
);

-- What was recommended, from which evidence watermark, with every candidate's score and the chosen reasons (23).
CREATE TABLE recommendations (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  session_id uuid REFERENCES sessions(id),
  policy_version text NOT NULL,
  source_watermark text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recommendations_user_created ON recommendations(user_id, created_at DESC);

GRANT SELECT, INSERT ON reports, ir_checkpoints, recommendations TO control_app;
