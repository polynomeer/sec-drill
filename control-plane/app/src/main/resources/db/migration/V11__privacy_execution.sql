-- V11: privacy data-subject execution (T14 ops, 14, 25, ADR 0013).
-- Mirrors the privacy execution section of SecDrill-docs/contracts/schema.sql.

-- A learner export is assembled into a private object and polled like an async job.
CREATE TABLE export_jobs (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES users(id),
  session_id uuid,
  status text NOT NULL CHECK (status IN ('ACCEPTED','RUNNING','COMPLETED','FAILED')),
  object_key text,
  digest text CHECK (digest ~ '^[a-f0-9]{64}$'),
  byte_size bigint CHECK (byte_size >= 0),
  requested_at timestamptz NOT NULL,
  completed_at timestamptz,
  FOREIGN KEY (session_id, owner_id) REFERENCES sessions(id, owner_id),
  CHECK ((status = 'COMPLETED') = (object_key IS NOT NULL AND digest IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX export_jobs_owner ON export_jobs(owner_id, requested_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON export_jobs TO control_app;

-- Dedicated least-privilege erasure role: only this role may run the executor, and the running application must
-- not remove identity linkage by itself.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'privacy_eraser') THEN
    CREATE ROLE privacy_eraser NOLOGIN;
  END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO privacy_eraser;
REVOKE DELETE ON user_identities FROM control_app;

-- Executes one APPROVED deletion request with the table owner's rights and a fixed search_path. Refuses a request
-- that is not APPROVED or was approved by its own owner; removes the subject's artifact rows, reports, checkpoints,
-- recommendations and -- for ACCOUNT scope -- identity linkage; writes tombstones; completes the request with its
-- receipt digest; returns the object keys whose bytes the caller must purge. Append-only ledger rows stay intact.
CREATE FUNCTION erase_deletion_request(p_request uuid, p_receipt text, p_now timestamptz) RETURNS SETOF text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_owner uuid;
  v_scope text;
  v_session uuid;
  v_status text;
  v_decided_by uuid;
BEGIN
  IF p_receipt !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'receipt digest is malformed'; END IF;
  SELECT owner_id, scope, session_id, status, decided_by INTO v_owner, v_scope, v_session, v_status, v_decided_by
    FROM deletion_requests WHERE id = p_request FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'deletion request % not found', p_request; END IF;
  IF v_status <> 'APPROVED' THEN RAISE EXCEPTION 'deletion request % is not APPROVED', p_request; END IF;
  IF v_decided_by IS NULL OR v_decided_by = v_owner THEN RAISE EXCEPTION 'deletion request % needs a separate approver', p_request; END IF;

  RETURN QUERY
  WITH target_sessions AS (
    SELECT id FROM sessions WHERE (v_scope = 'ACCOUNT' AND owner_id = v_owner) OR (v_scope = 'SESSION' AND id = v_session)
  ),
  doomed AS (
    SELECT a.id, a.object_key FROM artifacts a WHERE a.session_id IN (SELECT id FROM target_sessions)
  ),
  tomb_artifacts AS (
    INSERT INTO deletion_tombstones(subject_type, subject_id, deletion_request_id, applied_at)
    SELECT 'ARTIFACT', id, p_request, p_now FROM doomed ON CONFLICT (subject_type, subject_id) DO NOTHING
  ),
  del_reports AS (DELETE FROM reports WHERE session_id IN (SELECT id FROM target_sessions)),
  del_checkpoints AS (DELETE FROM ir_checkpoints WHERE session_id IN (SELECT id FROM target_sessions)),
  del_recommendations AS (
    DELETE FROM recommendations WHERE (v_scope = 'ACCOUNT' AND user_id = v_owner) OR (v_scope = 'SESSION' AND session_id = v_session)
  ),
  del_artifacts AS (DELETE FROM artifacts WHERE id IN (SELECT id FROM doomed)),
  tomb_sessions AS (
    INSERT INTO deletion_tombstones(subject_type, subject_id, deletion_request_id, applied_at)
    SELECT 'SESSION', id, p_request, p_now FROM target_sessions ON CONFLICT (subject_type, subject_id) DO NOTHING
  )
  SELECT object_key FROM doomed;

  IF v_scope = 'ACCOUNT' THEN
    DELETE FROM user_identities WHERE user_id = v_owner;
    INSERT INTO deletion_tombstones(subject_type, subject_id, deletion_request_id, applied_at)
      VALUES ('USER', v_owner, p_request, p_now) ON CONFLICT (subject_type, subject_id) DO NOTHING;
  END IF;

  UPDATE deletion_requests SET status = 'COMPLETED', completed_at = p_now, receipt_digest = p_receipt WHERE id = p_request;
END;
$$;
REVOKE ALL ON FUNCTION erase_deletion_request(uuid, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION erase_deletion_request(uuid, text, timestamptz) TO privacy_eraser;

-- Re-applies recorded tombstones after a backup restore (25): any tombstoned subject whose bytes or identity
-- linkage came back is removed again. Returns the object keys to purge. Eraser role only.
CREATE FUNCTION reapply_tombstones() RETURNS SETOF text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  DELETE FROM user_identities ui USING deletion_tombstones t WHERE t.subject_type = 'USER' AND t.subject_id = ui.user_id;
  RETURN QUERY
  WITH resurrected AS (
    SELECT a.id, a.object_key FROM artifacts a JOIN deletion_tombstones t ON t.subject_type = 'ARTIFACT' AND t.subject_id = a.id
  ),
  del AS (DELETE FROM artifacts WHERE id IN (SELECT id FROM resurrected))
  SELECT object_key FROM resurrected;
END;
$$;
REVOKE ALL ON FUNCTION reapply_tombstones() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reapply_tombstones() TO privacy_eraser;
