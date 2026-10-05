# 0008 CTF 플래그, 독립 목표 관측, 데모 결과 표시

- 상태: Proposed (T07 구현. strong runtime(D-10)에서 관측 경로를 guest 밖으로 옮기기 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: 없음(09·15·20의 요구를 구현). ADR-005(공식 점수는 rule/hidden gates)와 연결
- 관련: T07, FR-04, [09](../../SecDrill-docs/docs/09-ctf-wargame-guide.md), [10](../../SecDrill-docs/docs/10-evaluation-evidence.md), [15](../../SecDrill-docs/docs/15-api.md), [20](../../SecDrill-docs/docs/20-execution-grading.md), [ADR 0007](0007-lab-lifecycle-local-trusted-runtime-and-gateway.md)

## 문제와 제약

첫 CTF는 Session별 플래그로 목표 달성을 확인해야 한다. raw flag는 DB·로그·Outbox에 남기면 안 되고, 사용자 stdout이나 자체 성공 JSON으로 목표를 인정하면 안 된다. 다른 Session·종료된 Lab의 플래그는 거절해야 한다. strong runtime이 없어(D-10) Lab은 local-trusted에서만 돈다. 이 결과를 공식 결과처럼 보이게 하면 안 되고, 격리 요구를 몰래 낮추면 안 된다.

## 선택

- **플래그**: `SD{base64url(HMAC-SHA256(secret[keyVersion], len‖sessionId ‖ len‖challengeId ‖ len‖nonce))}`. nonce는 Lab generation마다 32바이트 난수로 `labs`에 저장하고 key version만 함께 둔다. 키는 `secdrill.ctf.keys`(version→base64url ≥32바이트)와 `active-key`. 키가 없으면 개발용 프로세스 키를 쓰고 `prod`는 기동을 거부한다. 플래그는 Lab claim 때 메모리에서 계산해 runner에 넘기고 docker CLI 환경으로(argv가 아님) 컨테이너에 전달한다. `LabSpec.toString`은 값을 가린다.
- **제출 검사**: 수락 트랜잭션 안에서 Session의 desired RUNNING Lab generation별로 상수 시간 비교한다. 결과(matched·labId·generation·keyVersion)는 flag 원문 없이 canonical JSON으로 만들어 flag key에서 domain 분리한 키로 HMAC 서명하고 PRIVATE_ORACLE artifact로 저장한다. submission에는 artifact 참조와 `challengeId·flagMatched·labId`만 남긴다. challenge는 고정된 버전의 FLAG challenge여야 하며(422), 최근 1분 오답 10건이면 429 `RATE_LIMITED`·`Retry-After: 60`.
- **독립 관측**: FLAG GRADE job은 그 Lab을 호스팅한 runner에게만 배정한다. runner는 flag 일치 여부를 모른 채 target 앱의 서버 측 접근 기록(`/tmp/secdrill/audit.jsonl`: 토큰의 tenant, 주문 소유 tenant, 응답 상태)을 runtime exec로 읽어 oracle `verifier.requires` 조건에 맞는 기록이 있는지만 보고한다. 기록은 HTTP로 제공되지 않는다. Control이 receipt와 관측을 합쳐 판정한다: 불일치 FAIL, 일치+관측 PASS, 일치+관측 없음 SYSTEM_ERROR(objective INCONCLUSIVE, 재시도 없음), 수집 실패·receipt 검증 실패는 PLATFORM_ERROR로 재시도 후 SYSTEM_ERROR. PASS일 때만 `OBJECTIVE_CONFIRMED`(COLLECTOR/OBSERVED) Evidence를 남긴다. 일반 worker(fake 포함)는 FLAG job을 받지 않는다.
- **데모 표시와 fail closed**: Lab에 `runtime_profile`과 `isolation_verified`(lab-strong이고 설정이 검증을 선언할 때만 true, DB CHECK)를 기록한다. content가 요구하는 profile보다 약한 pool에서는 `secdrill.lab.allow-unverified-isolation=true`(개발 전용, `prod` 기동 거부)일 때만 Lab을 만들고 아니면 503이다. `Evaluation.demo`는 fake worker이거나 격리 미검증 runtime 결과에서 true다. API의 `Lab.isolationVerified`와 `Evaluation.demo`는 필수 필드이고 UI는 배너와 결과에 "데모 결과(격리 미검증)"를 표시한다.
- **Session API**: `POST /v1/sessions`는 PUBLISHED 버전만(아니면 404), 지원 mode만(422) 받아 rubric·engine·randomization 버전을 고정한다. `POST /finish`는 모든 challenge에 활성 PASS가 있을 때만 SUBMITTED로 바꾸고 Lab 종료를 요청한다. 최종 리포트 job(T12)이 없어 COMPLETED로는 가지 않는다. `GET /v1/scenarios`는 서명된 cursor(프로세스별 키)로 paging한다.
- **local-trusted ingress relay**: Docker Desktop은 host에서 `--internal` network로 라우팅하지 않는다. 개발 adapter는 Lab마다 hardened relay 컨테이너(python digest 고정, `app:8080`만 전달, 127.0.0.1 publish)를 두고 그 주소를 endpoint로 보고한다. relay는 egress가 있는 bridge에도 붙으므로 Lab 자체의 차단과 별개의 local-trusted 위험이다.
- **Gateway 자격증명 전달 수정**: ADR 0007의 Gateway는 Cookie·Authorization을 모두 지웠다. Lab 앱의 로그인이 동작하지 않아 `lab_access`와 forwarding header만 지우도록 바꿨다. Gateway origin에는 플랫폼 cookie가 오지 않는다(host 분리, prod 검사).
- **최소 UI**: Control Plane이 제공하는 정적 HTML·JS(`/app/`, framework·build 없음, CSP `default-src 'self'`). Web 스택(D-06)은 T11에서 정하고 이 화면은 대체한다.

## 비교한 대안

- 제출 즉시 HTTP로 정답 여부 반환: 15의 비동기 verifier 원칙과 다르고 brute force 신호가 즉시 노출된다.
- Gateway에서 응답 본문의 플래그를 탐지해 관측: Gateway가 플래그를 알아야 하고, 플래그를 아는 것과 의도된 접근을 구분하지 못한다.
- 플래그만으로 PASS: 09가 금지한다(독립 관측기 없는 challenge는 INCONCLUSIVE).
- 로컬에서 Lab 포트를 host에 직접 publish: Gateway를 우회하는 경로가 생기고 격리 테스트의 "published port 없음"이 깨진다.
- content의 profile을 local-trusted로 낮춰 출판: 격리 요구를 낮추는 fallback이다.

## 비용과 위험

- **관측 기록은 Lab 안에 있다.** Lab에서 코드 실행을 얻은 학습자는 기록을 위조할 수 있다. 이 사건에는 그런 경로가 없다고 가정했다. strong runtime에서는 수집을 guest 밖(supervisor·gateway telemetry)으로 옮겨야 한다.
- 플래그는 Lab 컨테이너의 환경(`/proc/1/environ`)과 runner host의 `docker inspect`에 보인다. 학습자 경로에서 읽을 수 없도록 content가 보장해야 하며 검수 항목이다(09 검수 체크).
- 키 회전 시 이전 version을 남겨야 실행 중 Lab의 플래그가 검증된다. 폐기 절차는 D-16과 함께 정한다.
- receipt 검증 실패를 PLATFORM_ERROR로 재시도하는 것은 위조보다 저장소 손상을 가정한 선택이다. 감사 기록(`flag receipt invalid`)을 남긴다.
- cursor와 개발 flag 키는 프로세스별이라 재시작하면 cursor·실행 중 Lab 플래그가 무효다(local 개발 한정).
- relay 컨테이너는 egress가 있다. 외부 파일럿·학습자 공개에 쓰지 않는다.
- 실제 브라우저로 UI를 자동 검증하지 않았다(API·Gateway 경로만 자동 테스트).

## 검증 증거

- `CtfFlowTest` 6건: 실제 Docker(local-trusted, 합성 tenant-orders 이미지)·Gateway·내부 API로 로그인 tenant의 주문만 보이는 정상 접근, 타 tenant 주문에서 플래그 획득, 같은 키 재전송 replay, 일반 worker 미배정, 독립 관측 PASS·gates·demo 표시·`OBJECTIVE_CONFIRMED` OBSERVED Evidence, 같은 lease 중복 결과 STALE, 다른 learner 404, DB 7개 테이블·receipt·로그에 flag 없음, 학습자 응답에 oracle 값 없음, finish→SUBMITTED·Gateway 즉시 차단·cleanup receipt(app·relay container, 두 network)·Docker 잔여 없음·종료 후 제출 409. in-memory runtime으로 다른 Session·종료된 Lab 플래그 FAIL, 관측 없는 정답 SYSTEM_ERROR/INCONCLUSIVE와 finish 409, 변조 receipt 3회 재시도 후 SYSTEM_ERROR·감사, 오답 429·Retry-After, 미지 challenge 422, 미지원 mode 422·미출판 404, cursor paging·필터 결속
- `UnverifiedIsolationRefusedTest`: 개발 override 없이는 strong 요구 content의 Lab 요청이 503이고 Lab이 생기지 않음
- `FlagServiceTest` 3건: Session·challenge·generation·key version 결속, 키별 상이, receipt 서명 검증
- `LabGatewayTest`: Gateway cookie는 upstream에 가지 않고 Lab 앱의 cookie·Authorization은 전달
- 미검증: strong runtime에서의 동일 흐름, guest 밖 관측, 실제 브라우저 UI·SameSite 동작, 키 회전, 다중 Control 인스턴스의 cursor

## 결과와 되돌리는 조건

strong runtime이 생기면 `isolation_verified`가 true인 Lab만 공식 결과가 된다. 관측을 guest 밖으로 옮기면 `ObjectiveObserver`를 그 collector로 바꾸고 판정 표는 유지한다. Web 스택이 정해지면 정적 UI를 제거한다.

## 영향을 받는 문서·계약·테스트

`V7__ctf_flags_and_demo_results.sql`, `contracts/schema.sql`·`openapi.yaml`(Lab.isolationVerified·Evaluation.demo)·`fixtures/api.json`, 팩 10·13·14·15, `:control-plane:ctf`, `:control-plane:submission`(FLAG 수락·`CtfGradingService`·`GET /v1/submissions/{id}`), `:control-plane:lab`(Session API·profile gating), `:control-plane:catalog`(`GET /v1/scenarios`), `:execution:{protocol,agent}`, `:lab-gateway`, `content/labs/tenant-orders`, `/app/` 정적 UI, [T07](../development/T07.md)
