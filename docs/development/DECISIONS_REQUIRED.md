# 결정 필요 항목

마지막 갱신: 2026-10-04. 결정이 나면 상태를 바꾸고, 설계에 영향을 주는 결정은 [docs/adr/](../adr/README.md)에 ADR로 기록한 뒤 여기서는 링크만 남긴다. 근거가 된 발견 사항 F-xx는 [DESIGN_BASELINE](DESIGN_BASELINE.md)에 있다.

시점 구분: **지금**은 T01 착수 전, **Task 전**은 해당 Task 착수 전, **파일럿 전**은 실제 공격 코드를 실행하는 외부 파일럿 전에 결정한다. 담당은 현재 모두 프로젝트 소유자다.

## 요약

| ID | 항목 | 시점 | 상태 |
|---|---|---|---|
| D-01 | 계약 파일 위치 | 지금 | Open |
| D-02 | migration 형식과 PostgreSQL 버전 | 지금 | Open |
| D-03 | DDL 누락 보완(F-01~F-04, F-06, F-08) | 지금 | Open |
| D-04 | canonical digest와 hash chain | T05 전 | Open |
| D-05 | Control Plane 언어·프레임워크 | T02 전 | Open |
| D-06 | Web 스택 | T11 전 | Deferred |
| D-07 | build_pack 재생성 동작 | 팩 첫 수정 전 | Open |
| D-08 | message broker | T05 publisher 전 | Deferred |
| D-09 | OIDC provider | T02 실연동 전 / 파일럿 전 | Deferred |
| D-10 | 호스팅과 strong runtime | T06 실검증 전 / 파일럿 전 | Deferred |
| D-11 | 운영·법무 항목 | 파일럿 전 | Deferred |
| D-12 | 에이전트 간 작업 소유권 | 지금 | Proposed |

## 상세

### D-01 계약 파일 위치 (지금)

- 영향: 구현 코드·CI·생성기가 참조할 경로. 30은 루트 `contracts/`를 제안하고, 현재는 `SecDrill-docs/contracts/`가 단일 출처다(F-09).
- 선택지: (A) `SecDrill-docs/contracts/`를 계속 출처로 사용 (B) T01 첫 커밋에서 루트 `contracts/`로 `git mv`하고 팩 README·build_pack 참조 갱신.
- 권장: **A로 시작하고 T01 첫 변경에서 B 여부를 결정**. B를 택하면 복제하지 않고 이동만 한다. 앱 코드는 상수 하나로 경로를 참조해 이동 비용을 줄인다.

### D-02 migration 형식과 PostgreSQL 버전 (지금)

- 영향: T01 완료 증거인 "SQL integration".
- 선택지: (A) 번호 있는 순수 SQL(`V0001__core.sql`, Flyway 호환 이름) + 고정 digest의 PostgreSQL 컨테이너로 적용 테스트 (B) Flyway/Liquibase를 앱 스택과 함께 즉시 도입.
- 권장: **A**. 앱 스택(D-05)에 묶이지 않고, 나중에 Flyway가 같은 파일을 그대로 읽는다. PostgreSQL major는 착수 시 지원 상태를 확인해 정하고 이미지 digest로 고정한다. 로컬에는 Docker가 있다. 이미지 pull은 T01 착수 시 승인을 받는다.

### D-03 DDL 누락 보완 (지금)

- 영향: Lab 재생성 차단, 중복 provision, cross-owner 부모 연결, 멱등성 기준, 오류 코드(F-01~F-04, F-06, F-08).
- 선택지: (A) T01 migration에서 DESIGN_BASELINE 권장안대로 보완하고 13·14·15·16을 같은 변경에서 개정 (B) schema.sql을 그대로 옮기고 각 Task에서 보완.
- 권장: **A**. migration이 운영 단일 출처가 되기 전에 고치는 비용이 가장 낮다. 항목별 리뷰 후 수용한 것만 반영한다.

### D-04 canonical digest와 hash chain (T05 전)

- 영향: Idempotency body 비교, request digest, Evidence hash, 감사 재검증(F-05).
- 선택지: (A) RFC 8785 JCS + SHA-256, genesis hash `0`×64 (B) 구현 언어의 정렬 JSON 직렬화.
- 권장: **A**. 언어 독립적이고 Web·Agent·Control이 같은 값을 계산한다. ADR로 결정하고 cross-language fixture를 계약 테스트에 넣는다.

### D-05 Control Plane 언어·프레임워크 (T02 전)

- 영향: 모든 백엔드 Task, 빌드 도구, 의존성 고정 방식.
- 선택지: (A) 11의 제안대로 Kotlin/Spring Boot + JDK 21 LTS(로컬 설치됨) + Gradle wrapper (B) 다른 스택.
- 권장: **A를 개발용 가벼운 선택으로 채택**하고 ADR-001과 함께 Proposed ADR로 기록한다. patch 버전은 착수 시 지원 상태를 확인해 lockfile로 고정한다. 외부 파일럿 전에 재검토한다.

### D-06 Web 스택 (T11 전, 보류)

- 11은 TypeScript만 정한다. Web은 공개 OpenAPI만 의존하므로 T11 전까지 미뤄도 다른 Task를 막지 않는다.

### D-07 build_pack 재생성 동작 (팩 첫 수정 전)

- 영향: 팩 문서를 고친 뒤 생성물(ALL-IN-ONE·VALIDATION·MANIFEST) 갱신(F-10).
- 선택지: (A) 원본 스크립트를 그대로 쓰고, 실행 전 팩 내부 `.DS_Store`를 지운 뒤 생성된 ZIP은 무시(`*.zip`) (B) 스크립트를 고쳐 `.DS_Store`·`__pycache__`를 제외하고 ZIP을 `dist/`에 쓰며 문서·요구 개수를 하드코딩하지 않게 함.
- 권장: **B**. 별도 `chore(tooling)` 커밋으로 하고, 생성물 diff를 리뷰한다.

### D-08 message broker (T05 publisher 전, 보류)

- ADR-003 제안은 RabbitMQ quorum queue다. T05 앞부분(Outbox 원자 저장·inbox·claim·fencing)은 DB만으로 구현하고 테스트할 수 있다. publisher 구현 전에 RabbitMQ와 Redis Streams의 장애·중복·backlog 테스트 결과로 결정한다.

### D-09 OIDC provider (보류)

- ADR-009 미결정. T02는 로컬 테스트용 OIDC fake로 owner guard·CSRF·refresh reuse를 검증할 수 있다. 실제 provider는 외부 파일럿 전에 정한다. 실제 계정·client secret은 저장소에 두지 않는다.

### D-10 호스팅과 strong runtime (보류)

- ADR-002 microVM(Firecracker 계열 우선 검토)은 전용 runner host가 필요하다. T04·T06은 local-trusted fake로 개발하되 "미검증"으로 표시한다. 외부 공격 Lab 개방은 이 결정과 17의 출시 검증 뒤에만 한다.

### D-11 운영·법무 항목 (파일럿 전, 보류)

- 알림 수신자, 개인정보 처리 지역과 보관 예외, 제품·콘텐츠 라이선스, 운영자 2인 승인 인력, 최초 patch 이미지, 비용 한도. 내부 개발을 막지 않지만 02의 출시 승인 조건이다.

### D-12 에이전트 간 작업 소유권 (지금, Proposed)

- 권장안: [WORKFLOW](WORKFLOW.md#여러-에이전트를-함께-사용할-때)의 Task 단위 소유권과 인계 절차를 사용한다. 공통 enum·contracts·migrations·job protocol은 한 번에 한 소유자만 변경한다. 소유자 승인으로 Accepted가 된다.
