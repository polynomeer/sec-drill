# SecDrill 에이전트 공통 규칙

모든 코딩 에이전트(Claude Code, Codex 등)와 사람 기여자가 따르는 저장소 규칙이다. 작업 절차는 [WORKFLOW](docs/development/WORKFLOW.md), 커밋·리뷰는 [CONTRIBUTING](CONTRIBUTING.md)을 따른다. 현재 상태는 [IMPLEMENTATION_STATUS](docs/development/IMPLEMENTATION_STATUS.md)에서 확인한다.

## 기준 문서

- 설계 단일 출처는 [SecDrill-docs/](SecDrill-docs/README.md)다. 문서를 `docs/` 등에 복제하지 않는다.
- 충돌 시 우선순위: 공통 계약 `00` → MVP 범위 `03` → `SecDrill-docs/contracts/`. 그 외 문서는 제안이다.
- 설계 문서는 v0.1 제안 초안이다. 수치·스택·일정은 검증할 가정이며 구현 사실로 인용하지 않는다.
- `ALL-IN-ONE.md`, `MANIFEST.sha256`, `VALIDATION.json`은 생성물이다. 직접 수정하지 않고 [WORKFLOW](docs/development/WORKFLOW.md)의 재생성 절차를 따른다.
- `contracts/openapi.yaml`은 JSON 문법으로 작성되어 있고 도구가 JSON으로 읽는다. JSON 형식을 유지한다.

## 제품과 계약

- 제품명은 정확히 `SecDrill`로 표기한다. API 경로·DB 테이블·도메인 키에는 제품명을 넣지 않는다.
- 용어·enum·버전·상태 이름은 `00` 공통 계약과 contracts를 그대로 사용한다. 새 값을 임의로 만들지 않는다.
- MVP 범위(`03`) 밖 기능을 요청 없이 추가하지 않는다.
- 계약(API·이벤트·DB·enum·상태 전이) 변경은 관련 설계 문서, schema, 테스트, 추적표를 한 변경에서 함께 수정한다.
- 실제 구현에서 내린 설계 결정은 [docs/adr/](docs/adr/README.md)에 기록하고 원래 설계 문서와 연결한다. 검토되지 않은 결정을 Accepted로 표시하지 않는다.

## 작업 방식

- 구현·측정·검증한 결과와 계획·가정·미검증 항목을 구분해 기록하고 보고한다.
- 사용자 변경을 덮어쓰거나 되돌리지 않는다. 편집 전 `git status`와 대상 파일을 확인한다.
- 기존 패턴을 먼저 확인하고 작고 집중된 변경을 만든다. 관련 없는 리팩터링·포맷 변경·의존성 추가를 하지 않는다.
- 버그는 재현 가능한 실패를 먼저 만들고 근본 원인을 고친다. 증상만 가리는 재시도나 예외 삼키기를 하지 않는다.
- 의존성과 도구는 저장소 로컬 환경(`.venv/` 등)에 버전을 고정해 설치한다. 전역 환경을 바꾸지 않는다.

## 보안·신뢰 경계

- 비신뢰 코드 실행, 인증, 소유권, Evidence Ledger, 채점은 성공 경로뿐 아니라 실패·경합·중복·오래된 결과 경로까지 테스트한다.
- 강한 격리 runtime이 없으면 fake/local-trusted 구현임을 코드·테스트 결과·보고에 명시하고 격리 검증을 통과로 표시하지 않는다. strong isolation 요구를 낮추는 fallback을 만들지 않는다.
- 플랫폼 오류는 `SYSTEM_ERROR`/`INCONCLUSIVE`로 남긴다. 사용자 `FAIL`이나 점수 0으로 바꾸지 않는다.
- Oracle·플래그 정답·숨은 테스트·관리자 토큰을 Lab, learner-facing 산출물, 로그, 오류 응답에 넣지 않는다.
- 실제 비밀·고객 데이터·외부 공격 대상을 테스트·fixture·프롬프트에 사용하지 않는다. 합성 데이터만 사용한다.
- 비밀 파일(`.env*`, 키, 인증서, `secrets/`)을 읽거나 출력하지 않는다. 설정 템플릿은 `example.env` 또는 `*.example` 이름을 사용한다.

## Git과 외부 영향

- 커밋 메시지는 제목과 본문 모두 영어로 쓰고, [CONTRIBUTING](CONTRIBUTING.md#커밋-메시지)의 Conventional Commits 규칙과 일반적인 Git 메시지 관례를 따른다.
- 사용자가 요청하지 않은 commit, push, 배포, 새 브랜치 생성, 외부 업로드를 하지 않는다.
- 강제 push, 기존 커밋 재작성, 보호 브랜치 직접 push는 별도의 명시적 승인 없이는 하지 않는다.
- staging 전에 diff와 비밀 포함 여부를 확인하고 파일을 명시해 staging한다(`git add -A`/`git add .` 금지).

## 작업 종료 보고

작업을 마칠 때 다음을 보고하고 [IMPLEMENTATION_STATUS](docs/development/IMPLEMENTATION_STATUS.md)를 갱신한다.

1. 변경 내용(파일)
2. 검증 결과(실행한 명령과 결과)
3. 미검증 사항과 남은 위험
4. 다음 작업
