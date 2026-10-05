-- V7: CTF flags bound to a Lab generation, Lab runtime profile, demo marking of results (T07, ADR 0008).
-- Mirrors the T07 section of SecDrill-docs/contracts/schema.sql.

-- Session flags are HMAC(key version secret, sessionId || challengeId || nonce) (09). Only the nonce and the key
-- version are stored; the flag itself and the secret never are.
ALTER TABLE labs ADD COLUMN flag_nonce bytea CHECK (octet_length(flag_nonce) = 32);
ALTER TABLE labs ADD COLUMN flag_key_version text CHECK (flag_key_version ~ '^[a-z0-9-]{1,40}$');
ALTER TABLE labs ADD CONSTRAINT labs_flag_binding CHECK ((flag_nonce IS NULL) = (flag_key_version IS NULL));
-- The runner pool profile a Lab ran on (17). Only a verified strong runtime counts as verified isolation.
ALTER TABLE labs ADD COLUMN runtime_profile text CHECK (runtime_profile ~ '^[a-z-]{3,40}$');
ALTER TABLE labs ADD COLUMN isolation_verified boolean NOT NULL DEFAULT false;
ALTER TABLE labs ADD CONSTRAINT labs_isolation_profile CHECK (NOT isolation_verified OR runtime_profile = 'lab-strong');

-- Results from fake workers or unverified isolation are demo results, never official (prompt 08).
ALTER TABLE evaluations ADD COLUMN demo boolean NOT NULL DEFAULT false;
UPDATE evaluations SET demo = coalesce((dimensions->>'fake')::boolean, false), dimensions = '[]' WHERE jsonb_typeof(dimensions) = 'object';
ALTER TABLE evaluations ADD CONSTRAINT evaluations_shapes CHECK (jsonb_typeof(dimensions) = 'array' AND jsonb_typeof(gates) = 'array');
