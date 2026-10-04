# T01 계획: 공통 계약·validator·DB migration

상태: 계획(미착수). 원문: [30 구현 계획](../../SecDrill-docs/docs/30-implementation-plan.md) T01 "공통 enum·JSON/OpenAPI validator·DB migrations", 완료 증거 "contract parse, SQL integration".
관련 요구: FR-06, NFR-02(원자성·중복), FR-01(owner 범위 FK), FR-03(Lab 활성 제약), FR-07(Ledger append-only).

## 목표와 범위

다른 Task가 의존할 계약을 검증 가능한 형태로 고정하고, 핵심 DDL을 실제 PostgreSQL에서 적용·제약 테스트한다.

포함하지 않음: 앱 코드(Spring 모듈, Web), 인증 흐름, Outbox publisher, broker, Lab runtime. 언어별 enum 코드 생성은 해당 앱이 생기는 T02/T05에서 이 Task의 catalog를 읽도록 연결한다.

## 착수 전 결정

- [D-01](DECISIONS_REQUIRED.md#d-01-계약-파일-위치-지금) 계약 위치
- [D-02](DECISIONS_REQUIRED.md#d-02-migration-형식과-postgresql-버전-지금) migration 형식·PostgreSQL 버전
- [D-03](DECISIONS_REQUIRED.md#d-03-ddl-누락-보완-지금) DDL 누락 보완 범위

## 작업 단위

각 단위는 하나의 커밋(또는 작은 PR)이다.

| 단위 | 내용 | 파일 | 완료 기준 |
|---|---|---|---|
| T01-1 | 계약 위치 확정(D-01). 이동을 택하면 `git mv`와 참조 갱신만 | `SecDrill-docs/contracts/` 또는 `contracts/`, `scripts/check.py`, 팩 README | `scripts/check.py --strict` 통과, 복제본 없음 |
| T01-2 | enum·오류 코드 catalog. 00과 contracts의 enum, Job kind·state, 오류 코드(`IDEMPOTENCY_CONFLICT` 등, F-06)를 한 machine-readable 파일로 정의하고 parity 검사를 catalog 기준으로 확장 | `contracts/enums.json`(신규), `openapi.yaml` `Error`, `scripts/check.py` | catalog와 00·SQL·OpenAPI·event schema 불일치 시 검사 실패를 테스트로 확인 |
| T01-3 | 계약 보완(D-03): F-01~F-04, F-06, F-08 중 수용한 항목. 13·14·15·16 문서와 추적표 같이 개정 | `schema.sql`, `openapi.yaml`, `event.schema.json`, 팩 docs 13~16, ADR | 변경마다 문서·schema·검사 갱신, 생성물 재생성(D-07) |
| T01-4 | migration V0001: 보완된 schema.sql을 번호 있는 migration으로 옮기고 `idempotency_records` 포함. schema.sql은 설계 확인용으로 남기고 migration이 운영 단일 출처임을 명시 | `db/migrations/V0001__core.sql`(경로는 D-01·D-02 따름) | 빈 DB에 적용 성공, 재적용 거부 |
| T01-5 | DB 제약 통합 테스트: 고정 digest PostgreSQL 컨테이너에서 실행 | `db/tests/`, 실행 스크립트 | 아래 "DB 테스트 사례" 전부 통과, 결과에 PostgreSQL 버전·이미지 digest 기록 |
| T01-6 | 계약 fixture 테스트: OpenAPI 요청·응답 예제와 이벤트 타입별 정상·비정상 fixture를 validator로 검증 | `contracts/fixtures/`, `scripts/check.py` 또는 별도 테스트 | 10개 이벤트 타입 각각 정상 통과·비정상 거절, unknown enum 거절 |
| T01-7 | CI 연결: DB 통합 테스트 job 추가(서비스 컨테이너, digest 고정) | `.github/workflows/` | 로컬 결과와 동일. 원격 실행은 push 승인 후 |

## DB 테스트 사례

- 같은 `(session_id, client_request_id)` 두 번째 submission insert 실패
- 다른 owner의 Session을 가리키는 labs·parent_session 연결 실패(복합 FK)
- 한 owner의 두 번째 활성 Lab 실패, cleanup 확인 후 새 Lab 성공, 자원 없는 FAILED 뒤 새 Lab 성공(F-01)
- 같은 submission의 두 번째 `is_active` evaluation 실패
- `(session_id, seq)` 중복 evidence 실패, evidence UPDATE·DELETE가 trigger로 거절
- enum CHECK 밖 값(mode·status·verdict 등) 실패, `jobs.attempt` 4 실패
- digest 형식(64 hex) 위반 실패
- 중복 `(consumer, event_id)` inbox insert 실패

## 검증 방법

- `.venv/bin/python scripts/check.py --strict`
- DB 테스트 스크립트(T01-5에서 작성): 컨테이너 기동 → migration 적용 → 사례 실행 → 컨테이너 제거. 이미지 pull은 승인 후 수행한다.
- SQL 구문 검사(pglast) 통과를 실제 적용 검증으로 보고하지 않는다.

## 후속 Task와의 관계

- **T02**(OIDC·opaque session·owner guard·CSRF): T01의 오류 envelope·코드 catalog와 owner 범위 FK를 사용한다. `auth_sessions`와 `users`의 identity record migration은 T02가 V0002 이후로 추가한다. D-05(백엔드 스택)가 T02 착수 조건이다.
- **T05**(Outbox·inbox·claim·heartbeat·fencing): T01의 `jobs`(F-02 보완), `outbox_events`, `consumer_inbox`, `idempotency_records`, 이벤트 fixture를 사용한다. D-04(canonical digest)가 T05 착수 조건이다. broker(D-08)는 publisher 단계까지 미룰 수 있다.
- T03·T04·T09도 T01에 의존하지만 T02·T05 이후에 진행한다(31 실행 순서).
