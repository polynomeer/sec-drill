-- V9: hints given to a learner (T11, ADR 0011). Mirrors the T11 section of SecDrill-docs/contracts/schema.sql.

-- One row per hint level a Session received. Levels open in order; asking again never deducts again (06, 09).
CREATE TABLE hint_grants (
  session_id uuid NOT NULL REFERENCES sessions(id),
  challenge_id uuid NOT NULL,
  level integer NOT NULL CHECK (level BETWEEN 1 AND 4),
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, challenge_id, level)
);

GRANT SELECT, INSERT ON hint_grants TO control_app;
