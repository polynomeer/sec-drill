# 검증 범위

자동 검증은 두 갈래다. `scripts/check.py`는 문서·계약·설정·Git 제외 규칙을, `./gradlew check`는 Kotlin 빌드·단위 테스트·의존성 경계·실제 PostgreSQL 테스트를 검사한다. 제품 기능(인증·Session·Lab·채점)은 아직 없다.

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

JDK 21과 실행 중인 Docker가 필요하다. Docker가 없으면 DB 테스트는 skip되지 않고 실패한다.

| 검사 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|
| `ContractCatalogTest` | Kotlin enum·ErrorCode가 `enums.json`과 이름·순서·terminal·HTTP 상태·retryable까지 일치 | — |
| `KernelValuesTest` | canonical UUID만 허용, RFC 3339 offset 필수와 UTC `Z` 출력, retryable이 코드를 따름 | — |
| `ErrorEnvelopeTest` | 오류 봉투 형태, 잘못된 JSON·알 수 없는 enum·없는 경로·지원하지 않는 method·예상 외 예외의 코드와 상태, 내부 메시지 미노출 | 실제 API endpoint(미구현) |
| `CoreSchemaConstraintsTest` | PostgreSQL 18.6(digest 고정)에 V1·V2 적용 후 제약 15건: 멱등 키, owner 범위 FK, 활성 Lab 한도와 cleanup, job 대상·중복·attempt, active evaluation, evidence seq·append-only, enum·digest CHECK, inbox 중복, live refresh 1개·token hash 형식, 운영자 token 12시간, audit append-only, 수정된 migration checksum 실패 | 동시성 경합, 개인정보 삭제 함수(미구현) |
| `HealthReadinessTest` | migration 적용 후 기동, readiness는 DB 중지 시 503, liveness는 200 유지 | 운영 배포 환경 |
| `checkModuleBoundary` | `:shared:*`·`:execution:*` classpath에 JDBC·driver·pool·migration·ORM·`:control-plane:*` 없음, Control Plane domain 모듈(`:control-plane:identity` 등)이 `:control-plane:app`에 의존하지 않음 | 런타임 네트워크 접근(격리는 T06) |
| `AuthSessionFlowTest`·`OidcLoginTest`·`OperatorAuthTest`·`DevLoginTest` | 로그인(mock IdP의 PKCE·nonce·state), 만료·즉시 폐기, refresh 회전·재사용 시 로그인 폐기, cookie 속성, Origin·CSRF, 서버 session 미생성, 운영자 bearer 분리·audit | 실제 OIDC provider, 브라우저의 SameSite 처리 |
| `OwnershipGuardTest` | 다섯 자원 유형의 owner·타인·없음·삭제 판정, HTTP 404 본문 동일성 | 실제 자원 API(T04 이후) |
| `AuthSafetyTest` | dev-login을 local 밖이나 prod와 함께 켜면, prod에 OIDC·https origin이 없으면 기동 실패 | 운영 배포 설정 |

## 수행하지 않은 검증

- 제품 기능 API·E2E 테스트: 기능 미구현
- Linux 호스트에서의 Gradle·Testcontainers 실행: CI push 전이라 macOS에서만 확인
- strong runtime 격리, 네트워크 egress, 자원 제한, cleanup: T06
- 성능·부하·카오스: T14, 27의 지정 하드웨어 필요
- CI 워크플로 실제 실행: push 전이라 GitHub에서 실행되지 않음
