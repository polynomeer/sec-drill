# T01 계획: 공통 계약과 최소 실행 골격

상태: **로컬 구현·검증 완료, GitHub CI 미검증**(2026-10-04). 결정: D-01=A, D-02=Flyway+Testcontainers, D-03=6건 보완, D-05=Kotlin/Spring([ADR 0001](../adr/0001-control-plane-stack-and-migrations.md)), D-07=도구 수정. 실행 지침: [프롬프트 02](prompts/02-contract-foundation.md). 원문: [30 구현 계획](../../SecDrill-docs/docs/30-implementation-plan.md) T01 "공통 enum·JSON/OpenAPI validator·DB migrations", 완료 증거 "contract parse, SQL integration".
관련 요구: FR-06, NFR-02(원자성·중복), FR-01(owner 범위 FK), FR-03(Lab 활성 제약), FR-07(Ledger append-only).

## 목표와 범위

다른 Task가 의존할 계약을 검증 가능한 형태로 고정하고, Control Plane의 최소 실행 골격과 DB migration을 실제 PostgreSQL에서 검증한다.

포함: Control Plane 최소 구조, 공통 식별자·시각·enum·오류 봉투, 모듈 책임과 의존성 경계, 계약 검증과 기본 테스트 실행 경로, migration 체계, 개발용 환경변수 예제, health check, 실행 영역의 JDBC·Control DB 의존 금지 검사.

포함하지 않음: 제품 기능(인증·Session·제출 API 동작), 실제 Lab, AI, Web, Outbox publisher, broker.

## 착수 전 결정

- [D-05](DECISIONS_REQUIRED.md#d-05-control-plane-언어프레임워크-지금) Control Plane 스택: 02가 "확정된 스택"을 요구하므로 T01 전에 필요
- [D-02](DECISIONS_REQUIRED.md#d-02-migration-도구와-postgresql-검증-방식-지금) migration 도구와 PostgreSQL 검증 방식
- [D-03](DECISIONS_REQUIRED.md#d-03-ddl-누락-보완-지금) DDL 누락 보완 범위
- [D-01](DECISIONS_REQUIRED.md#d-01-계약-파일-위치-지금) 계약 위치

## 작업 단위

각 단위는 하나의 커밋(또는 작은 PR)이다. 경로는 `30`의 저장소 제안을 따르며 D-05 결과에 맞춰 조정한다.

| 단위 | 내용 | 파일(예) | 완료 기준 |
|---|---|---|---|
| T01-1 | 계약 위치 확정(D-01). 이동을 택하면 `git mv`와 참조 갱신만 | `SecDrill-docs/contracts/` 또는 `contracts/`, `scripts/check.py` | `scripts/check.py --strict` 통과, 복제본 없음 |
| T01-2 | enum·Job·오류 코드 catalog. 00과 contracts의 enum, Job kind·state, 오류 코드(F-06)를 machine-readable 파일 하나로 정의하고 parity 검사를 catalog 기준으로 확장 | `contracts/enums.json`, `openapi.yaml` `Error`, `scripts/check.py` | 불일치를 주입하면 검사 실패 |
| T01-3 | 계약 보완(D-03): F-01~F-04, F-06, F-08 중 수용 항목. 13·14·15·16과 추적표 같이 개정, 생성물 재생성(D-07) | `schema.sql`, `openapi.yaml`, `event.schema.json`, 팩 docs | 문서·schema·검사가 같은 변경에서 갱신 |
| T01-4 | 빌드 골격: build wrapper·버전 고정, 모듈 `control-plane/{identity,catalog,session,submission,evaluation,evidence,recommendation,operations,app}`, `execution/{protocol,orchestrator,agent}`. 빈 모듈 남발 없이 경계 검사에 필요한 최소만 | 빌드 설정, 각 모듈 최소 소스 | 빌드·빈 테스트 실행 성공 |
| T01-5 | 공통 커널: UUID ID 타입, UTC `Instant`·RFC3339 직렬화, catalog에서 온 enum, 오류 봉투 `{code,message,requestId,retryable,details}`와 HTTP 매핑(15) | 공통 모듈 | 직렬화·역직렬화 단위 테스트, 알 수 없는 enum 거절, details에 stack 미포함 |
| T01-6 | 의존성 경계 검사: `execution/*`에 JDBC·DB driver·Control repository 의존 금지, Control 모듈 간 내부 패키지 직접 참조 금지 | 빌드 검사 또는 아키텍처 테스트 | 금지 의존을 주입하면 빌드·테스트 실패 |
| T01-7 | migration V1: 보완된 schema.sql + `idempotency_records`를 번호 있는 migration으로 편입. schema.sql은 설계 확인용, migration이 운영 단일 출처 | `control-plane/app/src/main/resources/db/migration/` 등 | 빈 DB 적용 성공, checksum 변경 감지 |
| T01-8 | DB 제약 통합 테스트(실제 PostgreSQL, 이미지 digest 고정) | DB 통합 테스트 | 아래 "DB 테스트 사례" 전부 통과, PostgreSQL 버전·digest 기록 |
| T01-9 | 앱 기동과 health check: 개발용 `example.env`(비밀 없음), DB 연결 포함 readiness, 런타임 자동 DDL 비활성 | `example.env`, app 설정 | 로컬 기동 후 health 200, DB 중지 시 readiness 실패 |
| T01-10 | 계약 fixture 테스트: OpenAPI 요청·응답 예제와 이벤트 10종 정상·비정상 fixture | `contracts/fixtures/`, 테스트 | 각 타입 정상 통과·비정상 거절 |
| T01-11 | CI: 빌드·단위·DB 통합 테스트 job 추가 | `.github/workflows/` | 로컬 결과와 동일. 원격 실행은 push 승인 후 |

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
- 빌드 도구의 전체 테스트(단위·경계·DB 통합). 실제 PostgreSQL 컨테이너 이미지 pull은 착수 시 승인을 받는다.
- 앱 기동 후 health 확인
- SQL 구문 검사(pglast) 통과를 실제 적용 검증으로 보고하지 않는다.

## 후속 Task와의 관계

- **T02**([프롬프트 03](prompts/03-identity-ownership.md)): T01의 오류 봉투·코드 catalog·owner 범위 FK를 사용한다. `auth_sessions`와 identity record migration은 T02가 V2 이후로 추가한다.
- **T05**([프롬프트 04](prompts/04-async-foundation.md)): T01의 `jobs`(F-02 보완), `outbox_events`, `consumer_inbox`, `idempotency_records`, 이벤트 fixture, `execution/protocol` 경계를 사용한다. D-04(canonical digest)가 T05 착수 조건이다. broker(D-08)는 publisher 단계까지 미룰 수 있다.
- T03·T04·T09도 T01에 의존하지만 프롬프트 순서(03→04→05→06→07)를 따른다.

## 결과

| 단위 | 상태 | 증거 |
|---|---|---|
| T01-1 | 완료 | D-01=A, 이동 없음 |
| T01-2 | 완료 | `SecDrill-docs/contracts/enums.json`. `scripts/check.py` catalog 검사, `ContractCatalogTest` |
| T01-3 | 완료 | F-01~F-04·F-06·F-08 반영, 팩 13~16·README 개정, `build_pack.py` 재생성 |
| T01-4 | 완료 | Gradle 9.8.0 wrapper(배포본·jar SHA-256 확인), `:shared:kernel`, `:control-plane:app`, lockfile |
| T01-5 | 완료 | `secdrill.kernel`: ID·RFC 3339·enum·ErrorCode/ErrorEnvelope. `KernelValuesTest`, `ErrorEnvelopeTest` |
| T01-6 | 완료 | `checkModuleBoundary`. 금지 의존 주입 시 실패 확인. Control 모듈 간 경계 규칙은 해당 모듈이 생길 때 추가 |
| T01-7 | 완료 | `V1__core_schema.sql`, schema.sql과 SQL 동일 검사, 수정된 migration checksum 실패 테스트 |
| T01-8 | 완료 | `CoreSchemaConstraintsTest` 13건, PostgreSQL 18.6(digest 고정). 제약 제거 시 해당 테스트 실패 확인 |
| T01-9 | 완료 | `example.env`, `compose.yaml`, readiness·liveness 테스트, 로컬 `bootRun` health 200 |
| T01-10 | 완료 | `contracts/fixtures/` 이벤트 10+28건·API 14건 |
| T01-11 | 로컬만 | `build` job 추가. GitHub 실행은 push 전이라 미검증 |

