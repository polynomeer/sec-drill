# 검증 범위

자동 검증은 두 갈래다. `scripts/check.py`는 문서·계약·설정·Git 제외 규칙을, `./gradlew check`는 Kotlin 빌드·단위 테스트·의존성 경계·실제 PostgreSQL 테스트를 검사한다. 실제 채점과 strong isolation runtime은 아직 없다.

## 실행

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python scripts/check.py
```

`--strict`는 선택 검증기가 없어 SKIP된 항목도 실패로 처리한다. CI(`.github/workflows/contracts.yml`)는 `--strict`로 실행한다. 스크립트는 파일을 쓰지 않는다. `build_pack.py`의 `validate()`만 가져와 쓰고, 생성물을 다시 쓰는 main은 실행하지 않는다.

## 검사 목록

| 검사 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|
| 문서 번호·링크 | 팩 문서 35개 번호 00~34, 제목 접두어, 코드 펜스 짝, 팩 내부 상대 링크. 저장소 Markdown(팩 제외)의 상대 링크 | 외부 URL 생존, heading anchor, Mermaid 렌더링 |
| OpenAPI | OpenAPI 3.1 validator, 로컬 `$ref` 해석, operationId 유일성 | 실제 서버 응답 |
| 이벤트 JSON Schema | draft 2020-12 meta-schema, 정상 이벤트 1건 통과와 잘못된 payload 거절, 16의 타입 표와 schema enum·payload 규칙 일치 | 이전 schemaVersion과의 호환성 비교 |
| 시나리오·Oracle | versionId 일치, 모드·단계·challenge kind가 계약 안, runtime이 00 기본값, 대응 액션 4종, 가중치 합 100, 플랫폼 실패 INCONCLUSIVE, publishable false. publish를 막는 placeholder 3건 보고 | 실제 이미지 digest·서명, reference patch·mutant 실행 |
| SQL 구문 | `pglast`(PostgreSQL parser)로 schema.sql 구문 분석, V1 migration과 schema.sql의 SQL 동일성 | 실제 적용(아래 Gradle 검사가 담당) |
| 콘텐츠 schema | 예제 manifest·oracle이 두 JSON Schema(draft 2020-12)에 구조상 유효 | 출판 게이트(앱 검사가 담당) |
| catalog·fixture | `enums.json`과 00·SQL·OpenAPI·event schema의 enum·오류 코드 일치, 이벤트 10종 정상·비정상 fixture, API 본문 fixture | 런타임 요청·응답(API 미구현) |
| 요구사항 추적 | 02의 FR/NFR ID와 acceptance matrix 17건이 일치, 참조된 Task·문서 번호 존재, required gate에 Task 존재 | 실제 테스트 존재와 통과 |
| 프롬프트 | `docs/development/prompts/` 번호 00~22, 제목, 코드 펜스, 참조한 설계 문서 이름이 팩에 존재 | 프롬프트 내용의 타당성, 실행 결과 |
| manifest | MANIFEST.sha256의 해시가 파일과 일치, 누락·미등록 파일 | 팩 수정 뒤 생성물 재생성(WORKFLOW 절차) |
| 설정 형식 | `.claude/settings.json` JSON 문법, bypass 모드 비활성화, 전체 Bash 허용 없음, 개인 경로·토큰 패턴 없음, hook 파일 존재, `.editorconfig` | Claude Code가 규칙을 실제로 적용하는지(수동 확인 기록은 IMPLEMENTATION_STATUS) |
| Git 제외 규칙 | 비밀·개인 설정·venv·ZIP·raw 로그·제출물 샘플 경로가 제외되고 공유 파일은 추적됨 | 이미 커밋된 파일(`.gitignore`는 소급 적용 안 됨) |

## Gradle 검사

```bash
./gradlew check
```

JDK 21과 실행 중인 Docker가 필요하다. 통합 테스트는 PostgreSQL과 RabbitMQ 컨테이너(digest 고정)를 띄운다. Docker가 없으면 DB 테스트는 skip되지 않고 실패한다. Spring test context마다 컨테이너 한 쌍을 쓰므로 context cache를 4개로 제한하고(`src/test/resources/spring.properties`), 부하가 있는 Docker 호스트를 위해 컨테이너 기동 대기를 3분으로 둔다. Lab 격리 테스트는 busybox(digest 고정) 컨테이너와 network를 만들고 테스트마다 지운다.

| 검사 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|
| `ContractCatalogTest` | Kotlin enum·ErrorCode가 `enums.json`과 이름·순서·terminal·HTTP 상태·retryable까지 일치 | — |
| `KernelValuesTest` | canonical UUID만 허용, RFC 3339 offset 필수와 UTC `Z` 출력, retryable이 코드를 따름 | — |
| `ErrorEnvelopeTest` | 오류 봉투 형태, 잘못된 JSON·알 수 없는 enum·없는 경로·지원하지 않는 method·예상 외 예외의 코드와 상태, 내부 메시지 미노출 | 실제 API endpoint(미구현) |
| `CoreSchemaConstraintsTest` | PostgreSQL 18.6(digest 고정)에 V1~V8 적용 후 제약 16건: 멱등 키, owner 범위 FK, 활성 Lab 한도와 cleanup, job 대상·중복·attempt, active evaluation, evidence seq·append-only, enum·digest CHECK, inbox 중복, live refresh 1개·token hash 형식, 운영자 token 12시간, audit append-only, Lab desired state·READY·receipt 일관성, 수정된 migration checksum 실패 | 동시성 경합, 개인정보 삭제 함수(미구현) |
| `HealthReadinessTest` | migration 적용 후 기동, readiness는 DB 중지 시 503, liveness는 200 유지 | 운영 배포 환경 |
| `checkModuleBoundary` | `:shared:*`·`:execution:*`·`:content:*`·`:lab-gateway` classpath에 JDBC·driver·pool·migration·ORM·`:control-plane:*` 없음, Control Plane domain 모듈(`:control-plane:identity` 등)이 `:control-plane:app`에 의존하지 않음 | 런타임 네트워크 접근(격리는 T06) |
| `AuthSessionFlowTest`·`OidcLoginTest`·`OperatorAuthTest`·`DevLoginTest` | 로그인(mock IdP의 PKCE·nonce·state), 만료·즉시 폐기, refresh 회전·재사용 시 로그인 폐기, cookie 속성, Origin·CSRF, 서버 session 미생성, 운영자 bearer 분리·audit | 실제 OIDC provider, 브라우저의 SameSite 처리 |
| `OwnershipGuardTest` | 다섯 자원 유형의 owner·타인·없음·삭제 판정, HTTP 404 본문 동일성 | 실제 자원 API(T04 이후) |
| `SubmissionAcceptanceTest`·`CrashBeforeCommitTest` | 수락 원자성(commit 전 장애 시 전부 없음), raw flag 미저장, Idempotency replay·409·동시 요청, version CAS, 입력·권한 오류 | content 검증(T07~T10) |
| `OutboxDeliveryTest` | publisher confirm, broker pause 중 Outbox 보존과 재발행, 중복 전달의 단일 변경, poison DLQ | broker cluster·네트워크 분할 |
| `JobLeaseTest`·`FakeWorkerSafetyTest` | lease·heartbeat·fencing, 만료 결과 거절·감사, 3 attempt 뒤 SYSTEM_ERROR, CONTENT_INVALID, dispatch timeout, fake 결과 라벨과 local 전용 | 실제 runner·격리(T06) |
| `EvidenceLedgerTest` | 동시 append seq 연속, source event 중복 1건, 관측 시각과 seq 분리, 수정·gap·truncate 탐지, 신뢰 규칙(앱·DB), payload 금지 key, 다른 Session artifact FK, `control_app` 권한 거절, 삭제 계약 CHECK | 배포 런타임 역할, 서명 checkpoint |
| `ArtifactAndEvidenceApiTest`·`ArtifactStoreSafetyTest` | artifact owner 읽기·retention, 타인·oracle·삭제·만료 거절, 변조 bytes 거절, key traversal 거절, Evidence API paging·권한, 운영 로그에 flag·token 없음, prod에서 local store 거부 | S3 store, purge, 삭제 실행 |
| `ContentValidationTest`·`ContentCliTest` | 번들 digest·Ed25519 서명·정적 게이트·oracle 누출 탐지, 예제 출판 불가, runtime 없이 INCOMPLETE, CLI 키 파일 권한과 개인키 미출력 | 실제 runtime verifier, 이미지 registry 서명 |
| `ContentPublishingTest`·`ContentGateWithoutRuntimeTest` | 등록→검증→독립 승인→출판, 자기 승인 API·DB 거절, 역할 분리, 버전 불변·전진·차단, 공개 API·보고서·로그의 oracle 비노출, 기본 설정에서 출판 불가, prod의 test verifier 거부 | Web build 산출물(Web 없음) |
| `LabLifecycleTest`·`LabPoolQuotaTest` | in-memory runtime과 실제 내부 API로 Lab 요청·생성·중지·receipt, 중복·사용자·pool 한도, 취소 경합·late callback, STALE 보고, idle·hard TTL, orphan·RUNTIME_LOST, provisioning·cleanup 실패와 재시도·경보, 운영 중지, workload kind 범위 | 실제 runtime(아래), 다중 Control 인스턴스 |
| `LocalTrustedIsolationTest` | 실제 Docker의 local-trusted adapter: hardening·seccomp·namespace 거절, egress IPv4·IPv6·DNS·metadata·Control·다른 Lab 차단(양성 대조 포함), PID·memory·output 제한, Control 장애 중 hard TTL, late runtime 회수, 위조 label 미삭제 | **strong isolation(microVM)**, rootless daemon, Linux host |
| `LabGatewayTest` | 1회용 connect token·서명 검증, cookie 속성, credential header 제거, Lab의 access cookie 덮어쓰기 차단, CONNECT·absolute-form 거절, 중지 후 차단, allowlist 밖 거절 | Gateway→runner network 경로, 터미널 websocket, 다중 Gateway |
| `CtfFlowTest` | 합성 tenant-orders 이미지·Gateway·내부 API로 T07 흐름(정상 접근, 플래그 획득, 독립 관측 PASS, demo 표시, replay·중복 결과, DB·로그의 flag 부재, oracle 비노출, finish 회수), 다른 Session·종료 Lab 플래그 FAIL, 관측 없는 정답 SYSTEM_ERROR, 변조 receipt, 오답 429, Session·catalog API, 응답의 OpenAPI 필드 일치 | strong runtime, guest 밖 관측, 브라우저 UI 전체 흐름 |
| `PatchGradingTest` | 실제 Docker의 분리된 grading 환경과 외부 supervisor로 참조 패치 VERIFIED, 무수정·전부 거절·한 경로만·클라이언트 tenant·결과 조작·compile 오류 NOT_VERIFIED와 해당 gate, 탈출 probe로 grading 환경 출구 없음, 허용 경로·bundle digest, platform 오류·부분 결과·중복 결과·자료 누락 처리, hidden 정보 비노출 | grading-strong, 채점 감지형 패치 |
| `DetectionTest`·`IncidentModelTest` | 탐지 DSL 제한·평가·episode 지표·N/A·label 비노출·암기 규칙 holdout 실패, IR reducer 재현·네 액션 효과와 부작용·충돌·censored 지표 | 다른 사건 모델 |
| `ResponseDrillTest` | 액션 API(SIMULATED, replay digest, 409·422), 데이터셋 API(label 없음), DETECTION 수락·holdout 채점, Evidence 신뢰 수준 구분 | 실제 Lab 액션, Replay API |
| `UnverifiedIsolationRefusedTest`·`CtfSafetyTest`·`FlagServiceTest` | override 없이는 strong 요구 Lab·PATCH 제출 503, prod의 override·flag key 누락 거부, 플래그 결속·receipt 서명 | 키 회전 |
| `CanonicalJsonTest` | RFC 8785 벡터와 Evidence hash를 Python 구현과 동일하게 계산 | TypeScript 구현 |
| `AuthSafetyTest` | dev-login을 local 밖이나 prod와 함께 켜면, prod에 OIDC·https origin이 없으면 기동 실패 | 운영 배포 설정 |

## 수행하지 않은 검증

- E2E 테스트: Web·Session 생성 API 미구현
- Linux 호스트에서의 Gradle·Testcontainers 실행: CI push 전이라 macOS에서만 확인
- strong runtime(microVM) 격리: KVM 없는 호스트라 실행 불가(D-10). egress·자원 제한·cleanup은 local-trusted 프로파일에서만 확인
- 성능·부하·카오스: T14, 27의 지정 하드웨어 필요
- CI 워크플로 실제 실행: push 전이라 GitHub에서 실행되지 않음
