# SecDrill 초기화와 개발 규칙 준비

이 디렉터리를 SecDrill 개발 저장소로 초기화하고, Claude Code로 안정적으로 개발할 수 있도록 프로젝트 설정·문서 구조·작업 절차·커밋 규칙을 준비하라.

이번 작업은 개발 환경과 프로젝트 운영 기반을 마련하는 것이다. 제품 기능과 공격 Lab의 구현은 시작하지 않는다.

## 1. 현재 상태와 설계 확인

먼저 현재 디렉터리, Git 상태, 기존 파일, 적용되는 AGENTS.md·CLAUDE.md를 확인하라. 기존 작업과 사용자 변경을 보존하라.

SecDrill-docs/README.md를 읽고 다음 문서를 우선 확인하라.

- 00-common-contract.md
- 02-prd.md
- 03-mvp-scope.md
- 11-architecture.md
- 13-state-machines.md
- 14-database.md
- 15-api.md
- 16-events-async.md
- 17-sandbox-isolation.md
- 20-execution-grading.md
- 26-testing.md
- 29-adr-index.md
- 30-implementation-plan.md
- 31-claude-code-plan.md
- 34-sources-traceability.md

contracts/와 examples/도 확인하라.

문서에서 확정된 요구, 제안된 설계, 수치 가정, 구현 전 결정할 사항을 구분하라. 계약 충돌이나 구현을 막는 누락을 발견하면 명시하고 권장 해결안을 제시하라. 초안의 내용을 검증된 구현 사실로 취급하지 않는다.

## 2. Claude Code 설정 원칙

설치된 Claude Code 버전과 현재 프로젝트 설정을 확인하라. 설정 형식과 지원 기능은 해당 버전의 도움말과 공식 문서로 검증하라.

프로젝트 설정은 저장소 범위에 적용하고 전역 사용자 설정을 변경하지 않는다.

다음 원칙을 적용하라.

- 안전한 읽기·검색·로컬 검증은 효율적으로 수행한다.
- 비밀 파일, 자격증명, 개인키는 읽거나 출력하지 않도록 보호한다.
- 권한 검사 전체를 우회하는 설정은 사용하지 않는다.
- 설정에 실제 토큰·비밀번호·개인 경로를 넣지 않는다.
- MCP 서버, 플러그인, 외부 서비스는 필요한 이유 없이 추가하지 않는다.
- 필요하지 않은 도구와 의존성은 설치하지 않는다.
- 외부 업로드·배포·push·삭제처럼 영향을 크게 주는 작업은 프로젝트 지침과 사용자 승인을 따른다.
- 공유 설정과 개인 로컬 설정을 구분하고 로컬 설정은 Git에서 제외한다.

자동화 hook이 유용하면 작은 범위로 제안하고 설정 형식과 동작을 검증하라. 초기 hook은 빠르고 결정적인 검사에 한정한다. 매 편집마다 전체 테스트를 실행하거나, 파일을 조용히 수정하거나, 자동 commit·push하는 hook은 만들지 않는다.

지원 여부를 확인할 수 없는 설정은 추측해서 작성하지 말고 미적용 사유를 기록하라.

## 3. 저장소와 문서 구조

Git 저장소가 아니라면 현재 디렉터리에 저장소를 초기화하라. 상위 저장소에 이미 속해 있으면 중첩 저장소를 만들지 않는다. 기존 브랜치·remote·사용자 신원 설정을 변경하지 않는다.

SecDrill-docs/는 초기 설계 자료의 단일 출처로 유지하라. 동일 문서를 docs/에 다시 복제하지 않는다.

초기에는 다음 구조를 기준으로 준비하되, 필요 없는 빈 디렉터리나 앱 scaffold는 만들지 않는다.

- README.md: 프로젝트 목적, 현재 상태, 문서 진입점, 준비 절차
- AGENTS.md: 모든 코딩 에이전트가 공유하는 프로젝트 규칙
- CLAUDE.md: Claude Code의 짧은 진입 지침과 공통 규칙 참조
- CONTRIBUTING.md: 작업·검증·리뷰·커밋 절차
- .gitignore: 생성물·비밀·개인 설정 제외
- .editorconfig: 공통 텍스트 규칙
- .claude/: 검증된 프로젝트 설정과 필요한 작업 지침
- docs/development/: 개발 절차·작업 상태·환경 결정
- docs/adr/: 검토된 실제 설계 결정
- SecDrill-docs/: 제공된 설계 문서·계약·예제

CLAUDE.md와 AGENTS.md에 같은 규칙을 길게 중복하지 않는다. 공통 규칙은 AGENTS.md에 두고 CLAUDE.md에서 참조한다. Claude Code의 파일 참조 문법은 설치된 버전에서 지원되는 방식을 사용한다.

앞으로 실제 구현에서 결정한 ADR은 docs/adr/에 기록하고, 원래 설계 문서와 연결한다. 미수용 ADR 초안을 Accepted로 표시하지 않는다.

## 4. 에이전트 개발 규칙

AGENTS.md에 다음 내용을 명시하라.

- 제품명은 SecDrill로 통일한다.
- 용어·enum·버전·상태는 공통 계약을 따른다.
- MVP 범위 밖 기능을 요청 없이 추가하지 않는다.
- 계약 변경은 관련 문서·schema·테스트를 함께 수정한다.
- 구현·측정·검증 결과와 계획·가정을 구분한다.
- 사용자 변경을 덮어쓰거나 되돌리지 않는다.
- 기존 패턴을 확인하고 작고 집중된 변경을 만든다.
- 재현 가능한 실패와 근본 원인을 중심으로 수정한다.
- 관련 없는 리팩터링과 의존성 추가를 피한다.
- 비신뢰 코드 실행, 인증, 소유권, 원장, 채점은 실패 경로까지 검증한다.
- 강한 격리가 없으면 개발용 fake와 미검증 상태를 명시한다.
- 플랫폼 오류를 사용자 실패나 점수 0으로 바꾸지 않는다.
- 실제 비밀·고객 데이터·외부 공격 대상을 테스트에 사용하지 않는다.
- 사용자가 요청하지 않은 commit·push·배포·새 브랜치 생성은 하지 않는다.
- 작업 종료 시 변경 내용, 검증 결과, 미검증 사항, 다음 작업을 보고한다.

규칙 파일에는 일시적인 작업 기록이나 모든 설계 문서 본문을 넣지 않는다.

## 5. 커밋과 리뷰 규칙

CONTRIBUTING.md에 Conventional Commits 기반 규칙을 작성하라.

형식:
type(scope): summary

type:
feat, fix, docs, refactor, test, build, ci, chore, perf, revert

권장 scope:
identity, catalog, session, lab, submission, evaluation,
evidence, replay, simulation, recommendation,
execution, web, contracts, content, infra, docs, tooling

규칙:

- 커밋 제목은 간결한 영어 명령형으로 작성한다.
- 제목은 권장 72자 이내로 작성한다.
- 한 커밋은 하나의 검토 가능한 목적을 가진다.
- 동작 변경, 대규모 포맷 변경, 무관한 정리를 섞지 않는다.
- 복잡한 변경은 본문에 변경 이유·계약 영향·검증을 적는다.
- 호환성을 깨는 변경은 !와 BREAKING CHANGE 본문으로 설명한다.
- 비밀, raw Lab 로그, 사용자 제출물, 개인 설정, 생성 ZIP은 커밋하지 않는다.
- staging 전에 diff와 비밀 포함 여부를 확인한다.
- 자동으로 모든 파일을 staging하지 않는다.
- commit 생성은 사용자가 요청했을 때 수행한다.
- 강제 push, 기존 커밋 재작성, 보호 브랜치 직접 push는 별도 명시적 승인 없이 하지 않는다.

예시:

docs(project): establish development guidelines
feat(session): freeze scenario versions on session creation
fix(execution): reject results from expired leases
test(evaluation): cover mandatory regression gates

PR은 문제와 변경된 동작을 먼저 설명하고, 관련 요구사항·Task·ADR, 검증, 남은 위험을 포함하도록 템플릿을 마련하라. 변경과 관련 없는 체크리스트를 길게 만들지 않는다.

## 6. 작업 상태와 의사결정 기록

다음 문서를 작성하라.

docs/development/IMPLEMENTATION_STATUS.md
- 현재 단계와 활성 Task
- 완료 항목과 검증 근거
- 미검증 항목
- blocker
- 다음 작업
- 마지막 갱신일

docs/development/DECISIONS_REQUIRED.md
- 결정이 필요한 항목
- 영향과 선택지
- 권장안
- 지금 결정해야 하는지, 뒤로 미룰 수 있는지
- 결정 담당과 상태

docs/development/WORKFLOW.md
- 문서 확인 → 범위 설정 → 구현 → 검증 → 리뷰 → 상태 갱신
- 새 대화에서 읽을 최소 파일
- 작업 완료 기준
- 계약·상태·권한 변경의 필수 검토
- Claude Code와 Codex를 함께 사용할 때의 작업 소유권과 인계 절차

작업 상태 문서는 현재 상태를 유지한다. 모든 대화 내용을 계속 누적하는 긴 작업 일지로 만들지 않는다.

## 7. 초기 검증 기반

이번 단계에서 검증할 것은 문서와 설정, 계약이다.

제공된 tools/build_pack.py와 검증 기록을 확인하고 다음 검사를 재현 가능하게 정리하라.

- 문서 내부 링크와 번호
- OpenAPI 구조와 참조
- 이벤트 JSON Schema
- 시나리오·Oracle 예제 일관성
- SQL 구문
- 요구사항 추적표
- 설정 파일 형식
- Git 제외 규칙

검증 의존성이 필요하면 별도 개발 환경과 재현 가능한 버전 관리 방식으로 구성하라. 전역 환경에 임의로 설치하지 않는다. 필요한 경우 작은 검증용 스크립트나 CI를 추가하되, 아직 존재하지 않는 앱의 build·test가 통과했다고 표시하지 않는다.

SQL 구문 검증과 실제 PostgreSQL 마이그레이션·제약 동작 검증은 구별하라. 실제 runtime·성능·카오스 검증을 수행하지 않았다면 명시하라.

## 8. 다음 구현 단계 준비

30-implementation-plan.md의 T01을 다음 개발 작업으로 구체화하라.

다음 항목을 정리하라.

- T01의 작은 작업 단위
- 필요한 파일과 계약
- 완료 기준
- 검증 방법
- 후속 T02·T05와의 관계

제품 구현에 필요한 스택·runtime patch 버전·인증 공급자·호스팅을 이번 초기화에서 임의로 모두 확정하지 않는다. 개발을 진행할 수 있는 가벼운 선택과 외부 파일럿 전에 필요한 결정을 구분하라.

## 9. 수행과 최종 보고

먼저 짧은 실행 계획을 제시하고, 현재 승인된 범위 안의 가역적인 준비 작업은 계속 수행하라. 반복적으로 사소한 선택을 질문하지 않는다. 중요한 정보가 부족하면 이유와 권장안을 설명하라.

작업 완료 후 다음 내용을 간결하게 보고하라.

1. 생성·수정한 파일
2. 적용한 Claude Code 설정과 이유
3. 문서 충돌과 정리 결과
4. 검증 결과와 미검증 사항
5. 개발 전에 필요한 주요 결정
6. T01을 시작할 후속 프롬프트
7. 초기 커밋에 포함할 권장 파일과 커밋 메시지

실제 commit·push·배포는 수행하지 않는다.
