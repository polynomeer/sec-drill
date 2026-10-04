# 설계 기준선 검토

검토일: 2026-10-04. 대상: [SecDrill-docs v0.1](../../SecDrill-docs/README.md) 우선 문서 15종, `contracts/`, `examples/`.
이 문서는 설계를 구현 사실로 바꾸지 않는다. 아래 분류는 구현 전 해석 기준이며 결정은 [DECISIONS_REQUIRED](DECISIONS_REQUIRED.md)에서 관리한다.

## 분류

| 분류 | 의미 | 예 |
|---|---|---|
| 확정 요구 | 사용자 요구 또는 공통 계약 불변식. 변경 시 계약 개정 필요 | 모드·단계 enum, SYSTEM_ERROR와 FAIL 분리, Session 버전 고정, Oracle·플래그 Lab 비전달, Ledger append-only, CTF 점수와 역량 점수 분리, MVP 포함·제외 범위(`03`), P0 요구 FR-01~10·NFR-01~05 |
| 제안 설계 | 문서가 제안했지만 spike·리뷰 전인 설계. ADR 15건은 전부 Proposed | modular monolith(ADR-001), microVM Lab(ADR-002), Outbox+RabbitMQ(ADR-003), OIDC+opaque session(ADR-009), Kotlin/Spring·TypeScript 스택(`11`), 저장소 디렉터리 구조(`30`) |
| 수치 가정 | 측정 전 운영 가정. 실측 후 ADR로 변경 | 동시 50명/20 Lab, 2 vCPU·2 GiB·4 GiB·PID256, idle 15분·hard TTL 60분, heartbeat 10초·lease 30초·dispatch 120초, attempt 3회, p95 300ms/60초/30초, 보관 180일/30일, 파일럿 목표 수치(`02`) |
| 구현 전 결정 | 외부 계정·예산·운영 주체가 필요하거나 문서가 열어 둔 항목 | OIDC provider, 호스팅·strong runtime, 알림 수신자, 개인정보 처리 지역, 라이선스, 2인 승인 인력, patch 이미지, 비용 한도(`34`) |

계약 정합성은 `scripts/check.py`로 확인한다. enum 10종(00·SQL·OpenAPI), 이벤트 타입 10종(16·schema), 시나리오·Oracle 예제, 요구사항 추적표 17건이 서로 일치한다.

## 발견 사항

계약 간 충돌보다는 DDL·계약의 누락이 대부분이다. 권장안은 제안이며, T01에서 문서·schema·테스트를 함께 바꿀 때 적용한다.

| ID | 위치 | 내용 | 영향 | 권장 해결 |
|---|---|---|---|---|
| F-01 | schema.sql `labs` | 활성 Lab partial unique가 `cleanup_confirmed_at IS NULL` 기준이다. 잔여 자원 없이 `FAILED`가 된 Lab의 `cleanup_confirmed_at` 설정 규칙이 없고, `TERMINATED`와 `cleanup_confirmed_at`을 묶는 CHECK도 없다 | 사용자가 새 Lab을 영구히 만들지 못할 수 있음(FR-03) | "자원 없음이 확인된 FAILED는 전이 시 `cleanup_confirmed_at` 기록"을 13에 명시. `CHECK (state <> 'TERMINATED' OR cleanup_confirmed_at IS NOT NULL)` 추가 |
| F-02 | schema.sql `jobs` | PROVISION·CLEANUP job은 `submission_id`가 NULL이라 `UNIQUE(submission_id,kind,revision)`가 중복을 막지 못한다. Lab을 가리키는 컬럼도 없다 | 중복 provision job 가능, Lab↔job 추적 불가(FR-03, NFR-02) | `lab_id` 컬럼(복합 FK `lab_id,session_id`)과 kind별 nullability CHECK, `UNIQUE(lab_id,kind,revision)` 추가 |
| F-03 | schema.sql `sessions` | `parent_session_id` FK가 owner 범위가 아니다. 14는 "parent relation 검증"을 요구 | 타인 Session을 부모로 연결 가능(FR-01) | `FOREIGN KEY (parent_session_id, owner_id) REFERENCES sessions(id, owner_id)` |
| F-04 | 15 ↔ schema.sql | 15는 owner+route+key `idempotency_records`를 요구하지만 DDL에 없다. `submissions.client_request_id`와 `Idempotency-Key`의 관계가 정의되지 않았다 | T02·T05의 멱등성 구현 기준 불명확(FR-06) | `client_request_id = Idempotency-Key`로 명시. `idempotency_records`를 T01 migration에 포함 |
| F-05 | 14·15·16 | canonical body digest, Evidence hash chain 계산식, genesis `last_hash` 값이 정의되지 않았다 | request digest·hash가 구현마다 달라짐(FR-06, FR-07) | RFC 8785(JCS)+SHA-256, genesis 64자리 `0`, `hash = sha256(previous_hash ‖ seq ‖ payload_digest …)`를 ADR로 결정 |
| F-06 | openapi `Error` | `code`가 자유 문자열이다. `IDEMPOTENCY_CONFLICT`만 15에 있고 `details.latestVersion`·`missingGates`는 schema에 없다 | 클라이언트·테스트가 오류를 안정적으로 구분하지 못함 | 오류 코드 catalog를 enum으로 정의하고 details 필드를 schema화 |
| F-07 | scenario.json ↔ 15 | 예제 `patch.maxCompressedBytes`는 5 MiB이고 MVP inline PATCH는 256 KiB다. 15는 5 MiB를 후속 bundle 상한으로 설명 | 매니페스트를 inline 허용치로 오해할 수 있음 | 필드 의미를 "bundle 상한"으로 명시하거나 `inlineMaxBytes` 추가 |
| F-08 | 16 ↔ event.schema / API | `SessionCreated` payload는 `versionId`, API·DB는 `scenarioVersionId`다 | 이름 불일치 | producer가 없는 지금 `scenarioVersionId`로 통일(schemaVersion 1 유지 가능) |
| F-09 | 30 ↔ 저장소 운영 | 30은 루트 `contracts/`·`docs/`를 제안. 현재 계약은 `SecDrill-docs/contracts/`에 있고 단일 출처를 유지한다 | 구현 코드가 어디의 계약을 참조할지 미정 | D-01에서 결정. 이동 시 복제하지 않고 `git mv` 1회와 참조 갱신 |
| F-10 | tools/build_pack.py | 실행하면 ALL-IN-ONE·VALIDATION·MANIFEST를 다시 쓰고 ZIP을 팩 상위(저장소 루트)에 만든다. `rglob`이 `.DS_Store`를 manifest·ZIP에 포함한다. 문서 35개·요구 17개가 하드코딩 | 검증만 하려다 생성물이 바뀜. 문서 추가 시 검증 실패 | 검증은 `scripts/check.py`(read-only)로 수행. 재생성 시 `.DS_Store` 제외·ZIP 출력 위치 변경을 D-07에서 결정 |
| F-11 | contracts/openapi.yaml | 내용은 JSON이고 도구가 `json.loads`로 읽는다 | YAML 문법으로 편집하면 검증 도구가 깨짐 | JSON 형식 유지(AGENTS.md 규칙). 이름 변경은 D-01과 함께 검토 |
| F-12 | 00 ↔ openapi | MVP 독립 진입은 CTF·WARGAME·PURPLE이지만 `SessionCreate.mode`는 6개 값을 모두 허용한다 | 충돌은 아님. 422 거절이 도메인 책임 | T04에서 나머지 3개 모드 422 테스트 필수 |
| F-13 | 31 | 수정 금지 참조 디렉터리로 `sources/`를 언급하지만 존재하지 않는다 | 없음 | 무시. 생성물 3종을 수정 금지로 대신 지정 |

F-01~F-06은 T01·T02·T05를 막는 누락이다. F-07~F-13은 정리 사항이다.

## 처리 상태 (2026-10-04)

| ID | 상태 | 반영 |
|---|---|---|
| F-01 | 해결 | schema.sql·V1 CHECK, 13 Lab 절. DB 테스트 3건 |
| F-02 | 해결 | jobs `lab_id`·kind별 대상 CHECK·`UNIQUE(lab_id,kind,revision)`, 13·14. DB 테스트 |
| F-03 | 해결 | parent+owner 복합 FK, 14. DB 테스트 |
| F-04 | 해결 | `idempotency_records` 테이블, 14·15(`client_request_id` = Idempotency-Key). DB 테스트 |
| F-05 | 해결 | D-04·ADR 0003, 공통 벡터 |
| F-06 | 해결 | `contracts/enums.json` errorCodes, OpenAPI `Error.code` enum·details schema, 500 `INTERNAL_ERROR` 추가, 15 |
| F-07 | 미해결 | 시나리오 매니페스트 개정 시(T03) |
| F-08 | 해결 | event schema·16·build_pack 예제 `scenarioVersionId` |
| F-09 | 해결 | D-01: 현 위치 유지 |
| F-10 | 해결 | D-07: `.DS_Store` 제외, ZIP은 `dist/`, 개수 하드코딩 제거 |
| F-11 | 유지 | JSON 형식 유지 규칙(AGENTS.md) |
| F-12 | 미해결 | T04에서 422 테스트 |
| F-13 | 해당 없음 | — |
| F-14 | 해결 | 예제 manifest에 OpenAPI `Scenario`·`ScenarioDetail`이 요구하는 difficulty·estimatedMinutes가 없었음. 예제에 추가(T03) |
| F-15 | 해결 | 예제 oracle의 detection 비율이 부동소수였음. canonical digest(ADR 0003)와 맞게 basis point 정수로 변경(T03) |
