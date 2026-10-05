# 결정 필요 항목

마지막 갱신: 2026-10-05. 결정이 나면 상태를 바꾸고, 설계에 영향을 주는 결정은 [docs/adr/](../adr/README.md)에 ADR로 기록한 뒤 여기서는 링크만 남긴다. 근거가 된 발견 사항 F-xx는 [DESIGN_BASELINE](DESIGN_BASELINE.md)에 있다.

시점 구분: **지금**은 T01 착수 전, **Task 전**은 해당 Task 착수 전, **파일럿 전**은 실제 공격 코드를 실행하는 외부 파일럿 전에 결정한다. 담당은 현재 모두 프로젝트 소유자다.

## 요약

| ID | 항목 | 시점 | 상태 |
|---|---|---|---|
| D-01 | 계약 파일 위치 | 지금 | Decided: A(현 위치 유지), 2026-10-04 |
| D-02 | migration 도구와 PostgreSQL 검증 방식 | 지금 | Decided: Flyway + Testcontainers, 2026-10-04 |
| D-03 | DDL 누락 보완(F-01~F-04, F-06, F-08) | 지금 | Decided: T01에서 6건 모두 보완, 2026-10-04 |
| D-04 | canonical digest와 hash chain | T05 전 | Decided: RFC 8785 JCS + SHA-256, genesis `0`×64, 2026-10-04 |
| D-05 | Control Plane 언어·프레임워크 | 지금 | Decided: Kotlin + Spring Boot + Gradle + JDK 21, 2026-10-04 ([ADR 0001](../adr/0001-control-plane-stack-and-migrations.md) Proposed, 파일럿 전 재검토) |
| D-06 | Web 스택 | T11 전 | Deferred |
| D-07 | build_pack 재생성 동작 | 팩 첫 수정 전 | Decided: B(도구 수정), 2026-10-04 |
| D-08 | message broker | T05 publisher 전 | Decided: RabbitMQ 4.3 quorum queue, 2026-10-04 |
| D-09 | OIDC provider | 파일럿 전 | Deferred (구현은 provider 중립, [ADR 0002](../adr/0002-learner-and-operator-authentication.md)) |
| D-10 | 호스팅과 strong runtime | T06 실검증 전 / 파일럿 전 | Deferred |
| D-11 | 운영·법무 항목 | 파일럿 전 | Deferred |
| D-12 | 에이전트 간 작업 소유권 | 지금 | Proposed |
| D-13 | 프롬프트 묶음의 단일 출처 | 지금 | Decided: B(원본 묶음도 커밋), 2026-10-04 |
| D-14 | DB 역할 배포와 소유자 분리 | T14 전 | Open |
| D-15 | private Artifact store 구현 | T14 전 / 파일럿 전 | Open |
| D-16 | 콘텐츠 서명 키 운영과 이미지 서명 | T06 전 / 파일럿 전 | Open |
| D-17 | workload mTLS와 runner 노드 등록 | 파일럿 전 | Open |

## 상세

### D-01 계약 파일 위치 (지금)

- 영향: 구현 코드·CI·생성기가 참조할 경로. 30은 루트 `contracts/`를 제안하고, 현재는 `SecDrill-docs/contracts/`가 단일 출처다(F-09).
- 선택지: (A) `SecDrill-docs/contracts/`를 계속 출처로 사용 (B) T01 첫 커밋에서 루트 `contracts/`로 `git mv`하고 팩 README·build_pack 참조 갱신.
- 권장: **A로 시작하고 T01 첫 변경에서 B 여부를 결정**. B를 택하면 복제하지 않고 이동만 한다. 앱 코드는 상수 하나로 경로를 참조해 이동 비용을 줄인다.

### D-02 migration 도구와 PostgreSQL 검증 방식 (지금)

- 영향: T01 완료 증거인 "SQL integration"과 프롬프트 02의 "실제 PostgreSQL에서 migration과 주요 제약 검사".
- 선택지: 도구 (A) Flyway 순수 SQL migration (B) Liquibase (C) 자체 SQL runner. 검증 (가) Testcontainers로 테스트마다 PostgreSQL 컨테이너 기동 (나) docker compose로 띄운 DB에 테스트 연결.
- 권장: **A+가**. 프롬프트 02가 앱 골격을 포함하므로 D-05(Spring)와 함께 쓰는 Flyway가 가장 단순하고, migration이 순수 SQL로 남는다. Testcontainers는 테스트가 DB 수명을 스스로 관리해 CI와 로컬 결과가 같다. PostgreSQL major는 착수 시 지원 상태를 확인해 정하고 이미지 digest로 고정한다. 로컬에는 Docker가 있다. 이미지 pull은 착수 시 승인을 받는다.

### D-03 DDL 누락 보완 (지금)

- 영향: Lab 재생성 차단, 중복 provision, cross-owner 부모 연결, 멱등성 기준, 오류 코드(F-01~F-04, F-06, F-08).
- 선택지: (A) T01 migration에서 DESIGN_BASELINE 권장안대로 보완하고 13·14·15·16을 같은 변경에서 개정 (B) schema.sql을 그대로 옮기고 각 Task에서 보완.
- 권장: **A**. migration이 운영 단일 출처가 되기 전에 고치는 비용이 가장 낮다. 항목별 리뷰 후 수용한 것만 반영한다.

### D-04 canonical digest와 hash chain (T05 전)

- 영향: Idempotency body 비교, request digest, Evidence hash, 감사 재검증(F-05).
- 선택지: (A) RFC 8785 JCS + SHA-256, genesis hash `0`×64 (B) 구현 언어의 정렬 JSON 직렬화.
- 권장: **A**. 언어 독립적이고 Web·Agent·Control이 같은 값을 계산한다. ADR로 결정하고 cross-language fixture를 계약 테스트에 넣는다.

### D-05 Control Plane 언어·프레임워크 (지금)

- 영향: 모든 백엔드 Task, 빌드 도구, 의존성 고정 방식. 프롬프트 02가 T01에서 "확정된 스택"으로 Control Plane 골격을 만들도록 요구하므로 T01 전에 정한다(초기화 때는 T02 전으로 분류했음).
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
- 2026-10-05 확인: 개발 호스트(Apple M1 Max, Docker Desktop)에는 KVM이 없어 Firecracker를 실행할 수 없다. T04·T06은 local-trusted adapter로 구현했고 strong isolation은 **blocker**로 남겼다([T04_T06](T04_T06.md), [ADR 0007](../adr/0007-lab-lifecycle-local-trusted-runtime-and-gateway.md)). 결정에 필요한 것: KVM이 있는 Linux runner host(bare metal 또는 nested virtualization 지원 클라우드), runtime(Firecracker·Kata·gVisor) 선택, 같은 격리 테스트를 그 runtime에서 통과.

### D-11 운영·법무 항목 (파일럿 전, 보류)

- 알림 수신자, 개인정보 처리 지역과 보관 예외, 제품·콘텐츠 라이선스, 운영자 2인 승인 인력, 최초 patch 이미지, 비용 한도. 내부 개발을 막지 않지만 02의 출시 승인 조건이다.

### D-12 에이전트 간 작업 소유권 (지금, Proposed)

- 권장안: [WORKFLOW](WORKFLOW.md#여러-에이전트를-함께-사용할-때)의 Task 단위 소유권과 인계 절차를 사용한다. 공통 enum·contracts·migrations·job protocol은 한 번에 한 소유자만 변경한다. 소유자 승인으로 Accepted가 된다.

### D-13 프롬프트 묶음의 단일 출처 (지금)

- 영향: 같은 프롬프트가 `SecDrill-prompts/prompts/`와 `docs/development/prompts/` 두 곳에 있다. 묶음 README가 후자로 복사하도록 지시하고 "개별 파일이 단일 출처"라고 한다.
- 선택지: (A) `docs/development/prompts/`만 추적하고 `SecDrill-prompts/`는 커밋하지 않음(삭제 또는 로컬 보관) (B) 원본 묶음도 커밋해 출처 기록으로 남김(내용 중복, ALL-PROMPTS·MANIFEST가 수정 시 어긋남).
- 권장: A. 복사본은 원본과 동일함을 확인했다.
- 결정: **B**. `SecDrill-prompts/`는 출처 스냅숏으로 커밋하고 수정하지 않는다. 작업용 단일 출처는 `docs/development/prompts/`다.

### D-14 DB 역할 배포와 소유자 분리 (T14 전)

- 영향: append-only·삭제 권한 분리(14). 지금 migration이 `control_app` 역할을 만들지만 앱은 테이블 소유자로 접속한다.
- 선택지: (A) migration 전용 소유자와 런타임 로그인 역할(`control_app` 부여)을 분리하고 역할은 migration이 생성 (B) 역할을 인프라 코드로 미리 만들고 migration은 GRANT만.
- 권장: **B**. 관리형 PostgreSQL에서 CREATEROLE을 migration에 주지 않아도 된다. 어느 쪽이든 런타임은 소유자로 접속하지 않는다.

### D-15 private Artifact store 구현 (T14 전 / 파일럿 전)

- 영향: 제출 bundle·raw log·export 저장(11). 지금은 개발용 local filesystem store만 있고 `prod`에서 기동을 거부한다.
- 선택지: (A) S3 호환 object store(MinIO·클라우드) + 버킷 정책·versioning·암호화 (B) DB large object.
- 권장: **A**. 11의 제안과 같고 보관기간·versioning·서명 URL을 store가 제공한다. 호스팅(D-10)과 함께 정한다.

### D-16 콘텐츠 서명 키 운영과 이미지 서명 (T06 전 / 파일럿 전)

- 영향: 출판 게이트의 신뢰 근거(ADR 0006). 지금은 Ed25519 공개키를 설정(`secdrill.content.trusted-keys`)에 두고, 컨테이너 이미지는 digest만 manifest 서명에 포함된다.
- 선택지: 키 보관 (A) CI secret store의 Ed25519 키 + 정기 교체·폐기 목록 (B) KMS/HSM 서명. 이미지 (가) cosign 키 서명 (나) sigstore keyless.
- 권장: **A + 가**로 시작한다. 키 id별 유효기간과 폐기 목록을 설정에 두고, runner(T06)가 이미지 서명을 검증한 뒤에만 Lab을 만든다. 파일럿 전에 B 전환 여부를 재검토한다.
- 2026-10-05: T06 local-trusted 경로는 테스트 이미지 digest 고정만으로 진행했다. 이미지 서명 검증은 strong runtime으로 학습자 Lab을 열기 전 필수다.

### D-17 workload mTLS와 runner 노드 등록 (파일럿 전)

- 영향: Runner Agent·Lab Gateway의 Control 인증(11·19). 지금은 `runner_credentials`의 단기(≤24시간) bearer를 kind(AGENT·GATEWAY)별 경로로 제한한다([ADR 0007](../adr/0007-lab-lifecycle-local-trusted-runtime-and-gateway.md)).
- 선택지: (A) 내부 CA + 노드 등록 시 1회용 join token으로 단기 client 인증서 발급·자동 회전 (B) SPIFFE/SPIRE (C) 클라우드 workload identity.
- 권장: 호스팅(D-10)에 따라 정한다. 규모가 작으면 **A**로 시작하고, 어느 쪽이든 bearer는 폐기하고 runner quarantine 시 인증서를 즉시 폐기할 수 있어야 한다.
