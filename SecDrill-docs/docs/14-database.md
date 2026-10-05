# SecDrill DB 설계

PostgreSQL에 상태·권한·제출·원장을 저장하고 대용량 bytes는 private object store로 분리한다. [schema.sql](../contracts/schema.sql)은 핵심 모델의 실행 가능한 DDL 초안이며 auth·운영 부가 테이블은 아래 명세에 따라 구현한다.

## 테이블과 제약

| 테이블 | 핵심 컬럼 | 제약·인덱스 |
|---|---|---|
| users | id, pseudonym, created_at | email은 별도 암호화 identity record(MVP는 저장하지 않음) |
| user_identities | issuer, subject, user_id | issuer+subject PK; OIDC claim은 sub만 사용 |
| auth_sessions / auth_tokens | user_id, csrf_hash, revoked_at, revoke_reason / token_hash, kind, expires_at, superseded_at | 비밀은 SHA-256 hash만 저장; 로그인당 live refresh 1개 partial unique |
| operator_tokens | token_hash, operator_id, role, purpose, expires_at, revoked_at | 최대 12시간; learner 세션과 별도 |
| audit_events | actor_type, actor_id, purpose, action, occurred_at | UPDATE/DELETE trigger 거절 |
| scenarios / scenario_versions | id, slug / scenario_id, version_no, digests, manifest, author_id, bundle_digest, signature_key_id, quarantine | unique scenario_id+version_no; 내용 불변 trigger; 상태는 DRAFT→VALIDATED→PUBLISHED→QUARANTINED 방향만; VALIDATED는 PASS 보고서, PUBLISHED는 독립 승인 필요 |
| content_validation_reports / content_approvals | version, bundle_digest, verifier_kind, status, checks / version, author, reviewer, report | 보고서 append-only; 승인은 version당 1건, reviewer≠author CHECK, 같은 version의 보고서만 참조 |
| challenges | version_id, key, kind, public_spec | unique version_id+key; private oracle는 object ref |
| sessions | owner_id, version_id, mode, seed, status, phase, version, parent_id | owner+created_at; parent+owner 복합 FK로 같은 owner Session만 부모 |
| artifacts | session_id, key, digest, byte_size, sensitivity, deleted_at | private key unique; session scope FK |
| labs | session_id, owner_id, generation, state, desired_state, runtime_ref, runner_id, endpoint, expires_at, idle_expires_at, ready_at, terminate_reason, terminate_requested_at, cleanup_confirmed_at, cleanup_receipt | owner 활성 partial unique(cleanup 미확인); session+generation unique; TERMINATED는 cleanup 확인·receipt 필수; READY는 desired RUNNING·ready_at·runtime_ref 필수; desired TERMINATED ⇔ 종료 사유·시각; desired RUNNING 만료 index |
| runner_credentials | token_hash, runner_id, kind(AGENT·GATEWAY), issued_at, expires_at, revoked_at | 최대 24시간; hash만 저장; `/internal/**` 전용 workload bearer |
| submissions | session_id, kind, artifact_id, client_request_id, request_digest | session+client_request_id unique; artifact session 일치 |
| jobs | submission_id, lab_id, kind, state, attempt, fencing_token, worker_id, lease_until, last_error, result_digest | due job index; unique submission+kind+revision, lab+kind+revision; kind별 대상 CHECK; LEASED/RUNNING일 때만 lease·worker |
| idempotency_records | owner_id, route(실제 경로), idempotency_key, request_digest, response_status, response_body(text), expires_at | owner+route+key PK; 만료 index; 첫 응답을 byte 그대로 재반환 |
| evaluations | submission_id, revision, policy_version, verdict, dimensions, active | submission+revision unique; 활성 partial unique |
| ledger_heads / evidence | session_id, last_seq/hash / seq, type, payload_digest, hashes | session+seq unique; UPDATE/DELETE guard; 첫 hash는 `0`×64, 각 hash는 이전 hash를 포함한 JCS 객체의 SHA-256; source별 허용 trustLevel CHECK(USER는 USER_REPORTED만); session+source_event_id unique |
| deletion_requests / deletion_tombstones | owner, scope, session, status, decided, receipt / subject_type, subject_id, request | SESSION scope는 owner 일치 복합 FK; 완료는 receipt 필수; tombstone은 runtime 역할에 INSERT만 |
| outbox_events / consumer_inbox | envelope, published_at / consumer+event_id | 미발행 index; consumer+event_id unique |

추가 구현 테이블: applied_actions(session, seq, parameters, state_digest), reports(session, revision, evaluation_refs), skill_projections(user, policy, watermark, payload), export_jobs, deletion_requests. 실제 데이터와 같은 schema에서 마이그레이션으로 추가하고 API 작업 전 통합 테스트한다.

## 원자 작업

제출 생성 트랜잭션은 idempotency 확인·submission·job·outbox·evidence를 함께 저장한다. ledger_heads의 해당 Session row를 잠그고 seq·hash를 증가시킨다. 결과 반영은 job fencing CAS·evaluation insert·이전 active 해제·evidence·outbox를 같은 트랜잭션으로 커밋한다. partial unique 위반은 정상 중복과 잘못된 새 revision을 구분해 처리한다.

## 파티셔닝과 접근

MVP는 B-tree index와 기간별 삭제로 시작한다. evidence·telemetry 양이 커지면 월 단위 partition을 도입하되 session+seq 유일성을 보장하는 전략을 먼저 검증한다. FK를 다른 owner Session으로 연결하지 않도록 repository scope와 복합 FK를 사용한다. RLS는 방어층으로 검토하되 pooled connection의 tenant context 누수 테스트 없이 도입하지 않는다.

## 보관과 개인정보 삭제

Evidence metadata는 기본 180일, raw Lab 로그는 30일, 객체는 sensitivity별 TTL이다. append-only는 일반 앱 권한에 적용한다. 런타임 역할 `control_app`은 evidence·audit_events·deletion_tombstones에 UPDATE/DELETE 권한이 없고 trigger도 끌 수 없다. 삭제 담당 전용 역할은 승인된 deletion request에 따라 객체 bytes·identity 연결을 삭제하고 전체 만료 Session의 원장·head를 같이 제거할 수 있다. retained Ledger에는 가명 ID와 digest만 남기고 보고서에 payload unavailable을 표시한다. hash chain 유지가 개인정보 영구 보존의 근거는 아니다. backup 복원 후 deletion tombstone을 재적용한다.

제공 DDL의 evidence trigger는 기본 불변성만 강제한다. 운영용 privacy erasure는 별도 migration에서 일반 앱에 부여하지 않는 전용 역할·승인 요청·대상 Session 검증·감사 receipt를 갖는 제한 SECURITY DEFINER 함수로 구현한다. 함수의 search_path는 고정하고 실행 권한을 삭제 담당에게만 부여한다. 일반 API가 trigger를 끄는 방식은 금지한다. 이 함수와 실제 삭제 통합 테스트가 없으면 FR-10 출시 게이트를 통과하지 못한다.

## 마이그레이션과 복구

expand → backfill → app 전환 → contract 순서로 호환 변경한다. destructive migration은 백업·복구 검증 후 적용한다. 런타임 자동 DDL은 금지한다. schema.sql은 초기 설계 확인용이고 운영에서는 번호 있는 migration이 단일 출처다. 새 버전 컬럼은 기존 Session bundle에 default를 추정하지 않고 명시 backfill한다.
