# SecDrill

SecDrill은 격리된 환경에서 취약점을 찾고 공격을 재현한 뒤, 탐지·대응·수정을 하고 변형 공격과 새 과제로 실력을 검증하는 보안 실전 훈련 플랫폼이다. CTF·Wargame·Purple 세 모드를 제공한다. 제품 정의는 [설계 문서 세트](SecDrill-docs/README.md)를 따른다.

## 현재 상태

T13(MVP 콘텐츠·Transfer)까지 진행했다. 기본 3개·전이 3개 사건을 저작하고 참조 패치 VERIFIED·핵심 mutant 검출을 실제 채점으로 확인했다(데모·출판 전). T12에서 리포트는 차원별 근거 anchor·도움 수준·판정 범위와 이유가 있는 추천 3개를 보여주고, Replay는 기록과 모델 재계산을 나눠 checkpoint로 seek한다. T11에서 `web/`(React + Vite)이 공개 API로 카탈로그·CTF/Wargame/Purple 작업 공간을 제공한다. T10에서 탐지 규칙은 숨은 holdout 합성 로그로, 대응 액션은 seed가 고정된 모델로 평가하며 모두 `SIMULATED`로 기록한다. T08(Python 패치 채점)에서 패치는 job마다 새로 만드는 grading 환경에서 compile하고 별도 supervisor가 숨은 보안·회귀 테스트로 판정한다. 합성 tenant-orders 사건으로 Session 생성→Lab→Gateway→플래그 제출→독립 목표 관측→결과→회수 흐름이 API로 동작하며, **모든 결과는 데모(격리 미검증)로 표시된다.** T04·T06(Lab 수명과 실행 격리)은 개발 경로만 있다. Lab은 요청·quota·TTL·회수·별도 origin Gateway를 갖추고 `local-trusted`(hardened Docker)로만 실행된다. **strong isolation(microVM)은 미검증이라 외부 사용자에게 공개하지 않는다.** 서명된 콘텐츠 번들을 CLI와 내부 API로 등록·검증·독립 승인·출판·차단하지만, 실제 runtime 검증이 없어 기본 설정으로는 출판되지 않는다. Control Plane은 인증·owner guard, 제출 수락(Idempotency·Outbox), RabbitMQ 전달, job lease·fencing, 검증 가능한 Evidence Ledger와 조회 API, 개발용 private Artifact store를 제공한다. 개인정보 삭제 실행은 아직 없다. 채점은 **fake worker**(local 전용, 결과에 fake 표시)만 있으며 **기본·전이 콘텐츠 세트(T13)와 운영 기능(T14)은 아직 없다.** 설계 문서는 v0.1 제안 초안이며 수치·스택은 검증할 가정이다. 진행 상황은 [IMPLEMENTATION_STATUS](docs/development/IMPLEMENTATION_STATUS.md)에 있다.

## 문서 진입점

| 문서 | 내용 |
|---|---|
| [SecDrill-docs/](SecDrill-docs/README.md) | 설계 단일 출처: 문서 00~34, `contracts/`(OpenAPI·event schema·DDL), `examples/` |
| [AGENTS.md](AGENTS.md) | 모든 코딩 에이전트와 기여자가 따르는 규칙 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 검증·리뷰·커밋 규칙 |
| [docs/development/WORKFLOW.md](docs/development/WORKFLOW.md) | 작업 흐름, 새 대화에서 읽을 파일, 완료 기준, 에이전트 간 인계 |
| [docs/development/DECISIONS_REQUIRED.md](docs/development/DECISIONS_REQUIRED.md) | 결정이 필요한 항목과 권장안 |
| [docs/development/DESIGN_BASELINE.md](docs/development/DESIGN_BASELINE.md) | 설계 분류와 계약 검토 발견 사항 |
| [T01](docs/development/T01_PLAN.md), [T02](docs/development/T02.md), [T05](docs/development/T05.md), [T09](docs/development/T09.md), [T03](docs/development/T03.md), [T04·T06](docs/development/T04_T06.md), [T07](docs/development/T07.md), [T08](docs/development/T08.md), [T10](docs/development/T10.md), [T11](docs/development/T11.md), [T12](docs/development/T12.md), [T13](docs/development/T13.md) | Task별 계획과 결과 |
| [docs/development/prompts/](docs/development/prompts/) | 단계별 작업 프롬프트(00~22) |
| [docs/adr/](docs/adr/README.md) | 검토된 실제 설계 결정 |

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

Web: `cd web && npm ci && npm run build` 후 Control Plane의 `/app/`에서 열린다(개발 중에는 `npm run dev`). 로컬 실행: `docker compose up -d`로 개발 DB와 RabbitMQ를 띄우고 [example.env](example.env)의 값을 환경변수로 설정한 뒤 `./gradlew :control-plane:app:bootRun`. health는 `/actuator/health/readiness`. `SPRING_PROFILES_ACTIVE=local`이면 `POST /v1/auth/dev-login`(Origin `http://localhost:8080`)으로 로그인할 수 있다.

검사 범위와 미검증 항목은 [VERIFICATION](docs/development/VERIFICATION.md)에 있다.

Claude Code 사용자는 공유 설정 `.claude/settings.json`을 그대로 쓰고, 개인 설정은 `.claude/settings.local.json`에 둔다(Git 제외).
