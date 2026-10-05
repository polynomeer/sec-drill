-- V8: incident-response model actions (T10, ADR 0010). Mirrors the T10 section of SecDrill-docs/contracts/schema.sql.

-- One row per accepted model action, in Session order. Replaying (seed, engine, actions) reproduces state_digest.
-- Every row is SIMULATED: it changed the model, never a real Lab or VM (21).
CREATE TABLE applied_actions (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES sessions(id),
  seq integer NOT NULL CHECK (seq > 0),
  action_type text NOT NULL CHECK (action_type IN ('REVOKE_TOKEN','DISABLE_ENDPOINT','ISOLATE_WORKLOAD','ENABLE_AUDIT')),
  target text NOT NULL CHECK (length(target) BETWEEN 1 AND 128),
  tick integer NOT NULL CHECK (tick > 0),
  engine_version text NOT NULL,
  state_digest text NOT NULL CHECK (state_digest ~ '^[a-f0-9]{64}$'),
  representation text NOT NULL DEFAULT 'SIMULATED' CHECK (representation = 'SIMULATED'),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (session_id, seq),
  UNIQUE (session_id, tick)
);

GRANT SELECT, INSERT ON applied_actions TO control_app;
