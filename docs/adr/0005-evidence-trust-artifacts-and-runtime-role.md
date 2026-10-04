# 0005 Evidence 신뢰 규칙, private Artifact, 런타임 DB 역할

- 상태: Proposed (T09 기반 구현, 소유자 리뷰 대기)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-006(Ledger append-only + privacy artifact 분리)
- 관련: T09, FR-07, NFR-03, [10](../../SecDrill-docs/docs/10-evaluation-evidence.md), [14](../../SecDrill-docs/docs/14-database.md), [22](../../SecDrill-docs/docs/22-replay.md), [PRIVACY_ERASURE_REVIEW](../development/PRIVACY_ERASURE_REVIEW.md)

## 문제와 제약

공식 판정과 사용자 주장을 같은 신뢰 수준으로 저장하면 안 되고(10), 원장에는 비밀·raw source를 두지 않으며(10, 16), 일반 앱 권한으로는 원장을 바꿀 수 없어야 한다(14). 삭제는 전용 역할만 할 수 있어야 한다.

## 선택

- **신뢰 규칙**: SERVER_VERIFIED는 CONTROL·VERIFIER·SUPERVISOR, OBSERVED는 COLLECTOR·SUPERVISOR, SIMULATED는 SIMULATOR·SUPERVISOR, USER_REPORTED는 USER만. USER는 USER_REPORTED만 기록한다. 앱(`EvidencePolicy`)과 DB CHECK가 같은 규칙을 강제한다.
- **payload 위생**: ledger `safe_payload`에 `flag`·`token`·`secret`·`password`·`cookie`·`credential`·`source`·`content`·`code`·`patch`·`raw*` key와 512자 초과 문자열을 거절한다. raw 내용은 Artifact로 저장하고 원장은 artifact id와 digest만 갖는다.
- **append**: head row lock으로 seq·hash를 부여한다. `source_event_id`가 같으면 기존 entry를 반환한다. `occurredAt`(관측 시각)과 seq(서버 순서)를 분리한다.
- **검증**: `LedgerVerifier`가 payload digest, hash link, seq 연속성, head 일치를 다시 계산한다.
- **Artifact**: key는 `sessions/{sessionId}/{artifactId}`로 플랫폼이 만든다. row에 digest·크기·sensitivity·만료를 기록한다(RAW_LOG 30일, LEARNER·PUBLIC_SUMMARY 180일, PRIVATE_ORACLE은 콘텐츠 수명). learner 읽기는 owner guard를 거치고 PRIVATE_ORACLE·삭제·만료는 없음으로, digest 불일치는 내부 오류로 처리한다. 저장소는 개발용 local filesystem만 있고 `prod`에서 거부한다(D-15).
- **런타임 역할**: `control_app`은 evidence·audit_events·deletion_tombstones에 SELECT·INSERT만, evaluations·ledger_heads에 DELETE 없음. 배포에서 런타임 로그인 역할에 부여한다(D-14).
- **삭제 계약**: `deletion_requests`·`deletion_tombstones` 테이블만 추가했다. 실행 함수·승인은 미구현.

## 비교한 대안

- trigger만으로 append-only: 테이블 소유자가 trigger를 끌 수 있다. 권한 분리가 추가 방어층이다.
- 원장에 raw payload 저장: 삭제 시 hash chain과 충돌하고 개인정보를 영구 보존하게 된다.
- RLS: pooled connection의 tenant context 누수 시험 전에는 도입하지 않는다(14).

## 비용과 위험

- 런타임이 여전히 소유자로 접속하는 동안 권한 분리는 실효가 없다(테스트에서만 `SET ROLE`로 검증).
- payload key 금지 목록은 이름 기반이다. 값 안에 비밀을 넣는 호출자는 막지 못하므로 리뷰 항목으로 유지한다.
- bytes를 먼저 쓰고 row를 넣으므로 rollback 시 orphan bytes가 남는다(sweeper는 T14).
- DB 최고 권한자의 일관된 chain 재작성은 탐지하지 못한다(서명 checkpoint는 후속).

## 검증 증거

- `EvidenceLedgerTest` 9건: 동시 append 24건 seq 1~24·chain 정상, 같은 source event 1건, 늦은 관측 시각 보존, 수정·중간 삭제·마지막 삭제 탐지, 신뢰 규칙(앱·DB), payload 금지 key, 다른 Session artifact 참조 FK 거절, `control_app`의 UPDATE·DELETE·trigger 비활성화 거절, 삭제 계약 CHECK·FK
- `ArtifactAndEvidenceApiTest` 6건: owner 읽기·retention, 타인·oracle·삭제·만료 거절, 변조 bytes 거절, key traversal 거절, Evidence API paging·타인 404·401·422, 운영 로그에 raw flag·token 없음
- `ArtifactStoreSafetyTest`
- 미검증: S3 store, 실제 purge, 삭제 실행, 런타임 역할로 접속한 배포

## 결과와 되돌리는 조건

신뢰 규칙에 새 source가 생기면 enum·CHECK·`EvidencePolicy`·테스트를 함께 바꾼다. 서명 checkpoint를 도입할 때 verifier를 확장한다.

## 영향을 받는 문서·계약·테스트

`V4__evidence_and_privacy.sql`, 팩 14, catalog enum 3종, `:control-plane:evidence`, 위 테스트, [T09](../development/T09.md)
