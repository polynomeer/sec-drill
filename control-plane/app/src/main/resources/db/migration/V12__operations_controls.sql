-- V12: operations controls (T14 ops, 19, 25).
-- Mirrors the operations controls section of SecDrill-docs/contracts/schema.sql.

-- A draining pool refuses new Labs; existing Labs and leases are untouched.
CREATE TABLE lab_pool (
  id integer PRIMARY KEY CHECK (id = 1),
  draining boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO lab_pool(id) VALUES (1);
GRANT SELECT, UPDATE ON lab_pool TO control_app;

-- A quarantined Runner cannot claim work or have its results accepted (new claims blocked, current tokens discarded).
CREATE TABLE runner_quarantine (
  runner_id text PRIMARY KEY,
  reason text NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
  quarantined_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, DELETE ON runner_quarantine TO control_app;
