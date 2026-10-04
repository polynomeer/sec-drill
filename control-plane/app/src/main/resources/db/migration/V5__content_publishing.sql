-- V5: content authoring, validation reports, independent approval and publishing (T03, ADR 0006).
-- Mirrors the content section of SecDrill-docs/contracts/schema.sql.

-- Content roles join the operator roles (19). They authenticate on the operator chain only.
ALTER TABLE operator_tokens DROP CONSTRAINT operator_tokens_role_check;
ALTER TABLE operator_tokens ADD CONSTRAINT operator_tokens_role_check CHECK (role IN ('OPERATOR','SECURITY_ADMIN','AUTHOR','REVIEWER'));

ALTER TABLE scenario_versions ADD COLUMN author_id uuid;
ALTER TABLE scenario_versions ADD COLUMN bundle_digest text CHECK (bundle_digest ~ '^[a-f0-9]{64}$');
ALTER TABLE scenario_versions ADD COLUMN signature_key_id text CHECK (length(signature_key_id) BETWEEN 1 AND 100);
ALTER TABLE scenario_versions ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE scenario_versions ADD COLUMN quarantined_at timestamptz;
ALTER TABLE scenario_versions ADD COLUMN quarantine_reason text CHECK (length(quarantine_reason) BETWEEN 3 AND 500);
ALTER TABLE scenario_versions ADD CONSTRAINT scenario_versions_id_author UNIQUE (id, author_id);
ALTER TABLE scenario_versions ADD CONSTRAINT scenario_versions_signed_content
  CHECK (status = 'DRAFT' OR (author_id IS NOT NULL AND bundle_digest IS NOT NULL AND signature_key_id IS NOT NULL));
ALTER TABLE scenario_versions ADD CONSTRAINT scenario_versions_published_at CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL);
ALTER TABLE scenario_versions ADD CONSTRAINT scenario_versions_quarantine
  CHECK ((status = 'QUARANTINED') = (quarantined_at IS NOT NULL AND quarantine_reason IS NOT NULL));

-- Validation reports are append-only records of one validation run over one bundle digest (08, 26).
CREATE TABLE content_validation_reports (
  id uuid PRIMARY KEY,
  scenario_version_id uuid NOT NULL REFERENCES scenario_versions(id),
  bundle_digest text NOT NULL CHECK (bundle_digest ~ '^[a-f0-9]{64}$'),
  validator_version text NOT NULL,
  verifier_kind text NOT NULL,
  status text NOT NULL CHECK (status IN ('PASS','FAIL','INCOMPLETE')),
  checks jsonb NOT NULL,
  requested_by uuid NOT NULL,
  created_at timestamptz NOT NULL,
  UNIQUE (id, scenario_version_id)
);
CREATE INDEX content_validation_reports_version ON content_validation_reports(scenario_version_id, created_at);

-- One independent approval per version; the reviewer can never be the author (19).
CREATE TABLE content_approvals (
  scenario_version_id uuid PRIMARY KEY,
  author_id uuid NOT NULL,
  reviewer_id uuid NOT NULL,
  report_id uuid NOT NULL,
  approved_at timestamptz NOT NULL,
  FOREIGN KEY (scenario_version_id, author_id) REFERENCES scenario_versions(id, author_id),
  FOREIGN KEY (report_id, scenario_version_id) REFERENCES content_validation_reports(id, scenario_version_id),
  CHECK (reviewer_id <> author_id)
);

-- Version pinning (08, 12): content is immutable, status moves only forward, and the gates hold in the database.
CREATE FUNCTION guard_scenario_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF ROW(NEW.scenario_id, NEW.version_no, NEW.content_digest, NEW.oracle_digest, NEW.oracle_key, NEW.rubric_version,
           NEW.engine_version, NEW.randomization_version, NEW.public_manifest, NEW.author_id, NEW.bundle_digest, NEW.signature_key_id)
       IS DISTINCT FROM
       ROW(OLD.scenario_id, OLD.version_no, OLD.content_digest, OLD.oracle_digest, OLD.oracle_key, OLD.rubric_version,
           OLD.engine_version, OLD.randomization_version, OLD.public_manifest, OLD.author_id, OLD.bundle_digest, OLD.signature_key_id) THEN
      RAISE EXCEPTION 'scenario version content is immutable; publish a new version';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
      (OLD.status = 'DRAFT' AND NEW.status IN ('VALIDATED','QUARANTINED')) OR
      (OLD.status = 'VALIDATED' AND NEW.status IN ('PUBLISHED','QUARANTINED')) OR
      (OLD.status = 'PUBLISHED' AND NEW.status = 'QUARANTINED')) THEN
      RAISE EXCEPTION 'scenario version status cannot move from % to %', OLD.status, NEW.status;
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
      RAISE EXCEPTION 'published_at is fixed once set';
    END IF;
  END IF;
  IF NEW.status = 'VALIDATED' AND (TG_OP = 'INSERT' OR OLD.status <> 'VALIDATED') AND NOT EXISTS (
      SELECT 1 FROM content_validation_reports r
      WHERE r.scenario_version_id = NEW.id AND r.status = 'PASS' AND r.bundle_digest = NEW.bundle_digest) THEN
    RAISE EXCEPTION 'validation requires a passing report for this bundle digest';
  END IF;
  IF NEW.status = 'PUBLISHED' AND (TG_OP = 'INSERT' OR OLD.status <> 'PUBLISHED') AND NOT EXISTS (
      SELECT 1 FROM content_approvals a JOIN content_validation_reports r ON r.id = a.report_id
      WHERE a.scenario_version_id = NEW.id AND r.status = 'PASS' AND r.bundle_digest = NEW.bundle_digest) THEN
    RAISE EXCEPTION 'publishing requires an independent approval of a passing report';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER scenario_versions_guard BEFORE INSERT OR UPDATE ON scenario_versions
  FOR EACH ROW EXECUTE FUNCTION guard_scenario_version();

GRANT SELECT, INSERT ON content_validation_reports, content_approvals TO control_app;
REVOKE DELETE ON scenario_versions FROM control_app;
