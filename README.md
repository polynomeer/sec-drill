# SecDrill

SecDrill은 격리된 환경에서 취약점을 찾고 공격을 재현한 뒤, 탐지·대응·수정을 하고 변형 공격과 새 과제로 실력을 검증하는 보안 실전 훈련 플랫폼이다. CTF·Wargame·Purple 세 모드를 제공한다. 제품 정의는 [설계 문서 세트](SecDrill-docs/README.md)를 따른다.

## 현재 상태

T18(파일럿 출시 판정)까지 진행했다. **판정은 외부 학습자 파일럿 NO-GO이고, 모든 결과는 데모(격리 미검증)다.** 지금까지 구현·검증한 것과 못 한 것은 [IMPLEMENTATION_STATUS](docs/development/IMPLEMENTATION_STATUS.md)·[T18 dossier](docs/development/T18.md)에 있다.

구현·검증(데모 프로파일):
- **세 모드와 Purple 전 과정**: Session 생성→Lab→별도 origin Gateway→플래그 제출→독립 목표 관측→결과→회수가 API로 동작. 탐지(숨은 holdout 합성 로그)·대응(seed 고정 모델)은 `SIMULATED`로, 패치는 job마다 새 grading 환경에서 compile + 별도 supervisor의 숨은 보안·회귀 테스트로 판정.
- **콘텐츠**: 기본 3개·전이 3개 사건을 저작하고 참조 패치 VERIFIED·핵심 mutant 검출을 실제 채점으로 확인(데모). runtime verifier가 없어 **출판 게이트는 INCOMPLETE** — 기본 설정으로는 출판되지 않는다.
- **플랫폼**: 인증·owner guard, 제출 수락(Idempotency·Outbox), RabbitMQ 전달, job lease·fencing·stale 거절, 검증 가능한 Evidence Ledger·Replay·스킬·추천, 리포트, `web/`(React+Vite) 작업 공간.
- **운영·개인정보(T16)**: 개인정보 export·삭제 실행(전용 `privacy_eraser` 역할·2인 승인·tombstone·retention sweep), Lab pool drain·Runner quarantine, Micrometer 운영 지표·`X-Request-Id` 상관 id.
- **보안 리뷰(T15)**: 11개 우선순위 영역에서 학습자 악용 가능 결함 없음.

미검증·미구현(외부 공개 차단 사유):
- **strong isolation(microVM)은 미검증**(KVM 없는 호스트, D-10). Lab은 `local-trusted`(hardened Docker)로만 실행되며 외부 사용자에게 공개하지 않는다.
- **성능·부하·soak 미측정**(지정 하드웨어·staging 부재, [T17](docs/development/T17.md)). 설계 문서의 수치는 검증할 가정이며 실측이 아니다.
- DB 백업·복원 리허설, 실제 경보 수신자·운영 담당자 지정, 실제 OIDC(D-09)·배포 역할 분리(D-14)는 미완. 상세 조건은 [T18](docs/development/T18.md) B1~B7.

## 문서 진입점

| 문서 | 내용 |
|---|---|
| [SecDrill-docs/](SecDrill-docs/README.md) | 설계 단일 출처: 문서 00~34, `contracts/`(OpenAPI·event schema·DDL), `examples/` |
| [AGENTS.md](AGENTS.md) | 모든 코딩 에이전트와 기여자가 따르는 규칙 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 검증·리뷰·커밋 규칙 |
| [docs/development/WORKFLOW.md](docs/development/WORKFLOW.md) | 작업 흐름, 새 대화에서 읽을 파일, 완료 기준, 에이전트 간 인계 |
| [docs/development/DECISIONS_REQUIRED.md](docs/development/DECISIONS_REQUIRED.md) | 결정이 필요한 항목과 권장안 |
| [docs/development/DESIGN_BASELINE.md](docs/development/DESIGN_BASELINE.md) | 설계 분류와 계약 검토 발견 사항 |
| [T01](docs/development/T01_PLAN.md)…[T13](docs/development/T13.md), [T14](docs/development/T14.md), [T15](docs/development/T15.md), [T16](docs/development/T16.md), [T17](docs/development/T17.md), [T18](docs/development/T18.md) | Task별 계획·결과(보안 리뷰 T15, 운영·개인정보 T16, 성능·카오스 T17, 파일럿 판정 T18) |
| [docs/development/PORTFOLIO.md](docs/development/PORTFOLIO.md) | 문제→선택→구현→증거→한계 포트폴리오 |
| [docs/development/OPERATIONS.md](docs/development/OPERATIONS.md) | 배포·백업·경보·Runbook 운영 절차 |
| [docs/development/prompts/](docs/development/prompts/) | 단계별 작업 프롬프트(00~22) |
| [docs/adr/](docs/adr/README.md) | 검토된 실제 설계 결정(ADR 0001~0013) |

## 준비

요구 사항: Git, Python 3.14(검증기), JDK 21, Docker(DB 테스트·로컬 DB). Python 의존성은 저장소 로컬 `.venv`에만 설치하고, Gradle은 wrapper를 쓴다.

```bash
python3 -m venv .venv
```

```bash
.venv/bin/python -m pip install -r requirements-dev.txt
```

```bash
.venv/bin/python scripts/check.py
```

```bash
./gradlew check
```

Web: `cd web && npm ci && npm run build` 후 Control Plane의 `/app/`에서 열린다(개발 중에는 `npm run dev`).

로컬 실행(시작): `docker compose up -d`로 개발 DB(postgres)와 RabbitMQ를 띄우고 [example.env](example.env)의 값을 환경변수로 설정한 뒤 `./gradlew :control-plane:app:bootRun`. health는 `/actuator/health/readiness`. `SPRING_PROFILES_ACTIVE=local`이면 `POST /v1/auth/dev-login`(Origin `http://localhost:8080`)으로 로그인할 수 있다.

중지:

```bash
docker compose down
```

지원 범위:
- **모드**: CTF·Wargame·Purple(세 모드 모두 `web/` 작업 공간과 API로 제공).
- **콘텐츠**: 합성 사건 6개(기본 3 + 전이 3). 참조 VERIFIED·mutant 검출 확인(데모), 출판 게이트는 runtime verifier 부재로 INCOMPLETE.
- **격리 조건**: `local-trusted`(hardened Docker, 개발·CI 전용)만. 모든 결과는 데모로 표시된다. 강한 격리(microVM)가 검증·강제되기 전에는 외부 공격 Lab을 공개하지 않는다([T18](docs/development/T18.md)).
- **검증·채점 명령은 위 `scripts/check.py`·`./gradlew check`에서 확인**(이 저장소에서 통과). `bootRun`·성능 측정은 지정 하드웨어·staging에서 별도 검증.

검사 범위와 미검증 항목은 [VERIFICATION](docs/development/VERIFICATION.md)에 있다.

Claude Code 사용자는 공유 설정 `.claude/settings.json`을 그대로 쓰고, 개인 설정은 `.claude/settings.local.json`에 둔다(Git 제외).
