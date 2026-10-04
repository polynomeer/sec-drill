# SecDrill 전체 개발 프롬프트 통합본

작성일: 2026-10-04. 초기화 1개, 후속 프롬프트 22개와 추가 예제를 포함한다.

각 번호를 따로 실행한다. 모든 프롬프트를 한 번에 개발 요청으로 전달하지 않는다.

사용 방법과 복사 경로는 [README](README.md)를 따른다.


원본 파일: `prompts/00-project-initialization.md`

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


원본 파일: `prompts/01-initialization-review.md`

# 1. 초기화 결과 검토와 개발 시작 조건 확인

SecDrill 초기화 결과를 검토하라.

AGENTS.md, CLAUDE.md, CONTRIBUTING.md,
docs/development/의 작업 상태·결정 목록·작업 절차를 읽어라.

SecDrill-docs의 공통 계약, PRD, MVP 범위, 아키텍처,
구현 계획, contracts와 현재 저장소를 비교하라.

다음을 확인하고 필요한 정리를 수행하라.

- 문서·설정의 중복과 충돌
- 확정 요구와 아직 검증하지 않은 가정
- T01을 시작하는 데 필요한 결정
- 외부 파일럿 전에만 필요한 결정
- 설정과 검증 도구의 실제 동작

T01을 막는 결정에는 선택지와 권장안을 제시하라.
기존 세션에서 이미 확정된 선택은 다시 질문하지 않는다.
미결정 사항을 몰래 구현 기본값으로 확정하지 않는다.

앱 기능은 아직 구현하지 않는다.
결과를 IMPLEMENTATION_STATUS.md와 DECISIONS_REQUIRED.md에 반영하고,
다음 작업의 범위와 완료 기준을 보고하라.


원본 파일: `prompts/02-contract-foundation.md`

# 2. T01 공통 계약과 최소 실행 골격 구현

SecDrill T01을 구현하라.

00-common-contract, 11-architecture, 12-domain-model,
13-state-machines, 14-database, 15-api, 16-events-async,
30-implementation-plan과 contracts를 읽어라.

확정된 스택을 사용해 다음을 준비하라.

- Control Plane의 최소 실행 가능한 구조
- 공통 식별자·시각·enum·오류 봉투
- 모듈별 책임과 의존성 경계
- 계약 검증과 기본 테스트 실행 경로
- DB migration 체계
- 개발용 환경변수 예제와 health check

이번 변경에 제품 기능, 실제 Lab, AI 기능을 추가하지 않는다.
실행 영역에 JDBC나 Control DB 의존성이 들어가지 않도록
빌드 또는 구조 검사를 마련하라.

제공 SQL을 그대로 운영 migration이라고 간주하지 말고,
스키마 누락·제약·버전 호환성을 검토한 뒤 편입하라.
실제 PostgreSQL에서 migration과 주요 제약을 검사하라.

실행·검증 방법과 결과를 기록하고 작업 상태를 갱신하라.


원본 파일: `prompts/03-identity-ownership.md`

# 3. T02 인증과 리소스 소유권 구현

SecDrill T02를 구현하라.

02-prd, 15-api, 18-threat-model, 19-iam과
현재 인증 관련 결정·ADR을 읽어라.

확정된 인증 방식을 구현하고 다음을 검증하라.

- 로그인과 로그아웃
- 세션 만료와 폐기
- refresh 회전·재사용 감지
- cookie 속성과 CSRF·Origin 검사
- 본인 리소스만 접근하는 공통 guard
- 운영자 인증과 일반 사용자 인증의 분리

로컬 인증 대체 수단이 필요하면 개발 환경에서만 활성화하고,
운영 설정에서 활성화되면 기동 또는 검증이 실패하게 하라.

타인 Session·Submission·Artifact·Report·Evidence에 대한 접근은
문서의 존재 은폐 정책을 따르도록 하라.
아직 없는 리소스는 guard 계약과 테스트 fixture로 검증하되,
관련 API 구현 때 동일 검사를 연결해야 한다는 사실을 기록하라.

권한·인증 실패 경로를 테스트하고 작업 상태를 갱신하라.


원본 파일: `prompts/04-async-foundation.md`

# 4. T05 신뢰할 수 있는 비동기 작업 기반 구현

SecDrill T05를 구현하라.

13-state-machines, 14-database, 16-events-async,
20-execution-grading과 이벤트 계약을 읽어라.

다음을 작은 변경 단위로 구현하라.

- Submission과 Outbox의 원자 저장
- Idempotency-Key와 요청 digest 검사
- Publisher와 broker 확인
- Consumer inbox와 중복 처리
- Job 상태·dispatch timeout·lease
- heartbeat와 fencing token
- 제한된 재시도·DLQ·감사 기록

이 작업에서는 실행을 fake worker로 연결한다.
fake와 실제 실행 결과는 명확히 구별한다.

필수 검증:
동일 키·동일 입력은 같은 응답,
동일 키·다른 입력은409,
커밋 전 장애는 둘 다 저장되지 않음,
커밋 후 broker 장애는 Outbox 보존,
중복 이벤트는 업무 변경을 중복 수행하지 않음,
만료된 lease의 결과는 공식 판정에 반영되지 않음.

메시지의 exactly-once 전달을 가정하지 않는다.
플랫폼 실패를 사용자 실패로 바꾸지 않는다.


원본 파일: `prompts/05-evidence-artifacts.md`

# 5. T09 Evidence Ledger와 Artifact 기반 구현

SecDrill T09의 기반을 구현하라.

10-evaluation-evidence, 14-database, 16-events-async,
22-replay와 개인정보·삭제 관련 문서를 읽어라.

다음을 구현하라.

- Session별 seq와 head 갱신
- canonical payload digest와 hash chain
- 증거 source·trustLevel 구분
- 일반 앱 권한의 append-only 제약
- private Artifact 저장·digest·owner 검사
- raw payload와 원장 metadata 분리
- 보관기간과 삭제 처리의 기초 계약

공식 판정과 사용자 주장을 같은 신뢰 수준으로 저장하지 않는다.
raw flag·token·secret·사용자 소스를 운영 로그에 남기지 않는다.

동시 append, 중복 이벤트, 순서, 해시 검증,
다른 Session Artifact 연결, 접근 권한을 테스트하라.

개인정보 삭제의 전용 역할·승인·감사 경로를 검토하라.
실제 삭제 구현이 아직 없다면 완료로 표시하지 않는다.


원본 파일: `prompts/06-content-publishing.md`

# 6. T03 콘텐츠 저작·검증·출판 기반 구현

SecDrill T03을 구현하라.

08-content-guide, 09-ctf-wargame-guide, 29-adr-index,
examples와 공개·비공개 계약을 읽어라.

다음을 구현하라.

- 공개 manifest와 private oracle의 분리
- 버전과 canonical digest
- 이미지·콘텐츠 서명 검증
- 참조 해답·mutant·seed 검증 인터페이스
- 검증 보고서
- 작성자와 승인자의 분리
- 출판·차단·버전 고정

아직 실제 runtime 검사가 없다면 검증 결과를 구분하고,
검증하지 않은 번들이 출판 게이트를 통과하지 못하게 하라.

예제의 placeholder digest나 publishable=false를
실제 출판 가능한 데이터로 임의 변경하지 않는다.

공개 API·Web build·로그에서 oracle·정답·플래그 비밀이
노출되지 않는지 검사하라.
운영 화면은 필요하지 않으면 CLI·내부 API로 시작하라.


원본 파일: `prompts/07-lab-isolation.md`

# 7. T04·T06 Lab 수명과 강한 실행 격리 구현

SecDrill T04와 T06을 단계적으로 구현하라.

11-architecture, 13-state-machines, 17-sandbox-isolation,
18-threat-model, 19-iam, 25-operations-deployment를 읽어라.

먼저 선택한 runtime과 현재 host의 지원 조건을 확인하라.
실제 strong runtime 실행을 검증할 수 없으면
개발용 fake 경로와 실제 검증의 blocker를 분리해 기록하라.
격리 수준을 낮춰 완료로 처리하지 않는다.

구현 범위:

- Lab desired state와 generation
- 사용자·pool quota
- provisioning과 runtime ownership label
- 별도 origin의 인증 Gateway
- 허용 대상만 연결하는 proxy
- idle·hard TTL
- stop·kill·delete와 cleanup receipt
- 취소 경합·late callback·orphan reconciliation
- Agent의 제한된 workload identity

실제 검증:
외부 egress, metadata, Control Plane, 다른 Lab,
IPv4·IPv6·DNS 우회 접근 차단,
host mount·socket 부재,
자원·PID·출력 제한,
Control 장애 중 hard TTL,
취소 후 생성된 자원의 회수.

이 단계에서는 외부 사용자에게 공개하지 않는다.


원본 파일: `prompts/08-first-ctf.md`

# 8. T07 첫 사건 CTF 수직 기능 구현

SecDrill의 첫 실제 수직 기능을 완성하라.

03-mvp-scope, 05-learning-loop, 09-ctf-wargame-guide,
20-execution-grading을 읽고 tenant leak 사건 하나를 구현하라.

흐름:
로그인 → 사건 선택 → 고정 버전 Session 생성 →
Lab 준비 → 합성 목표 접근 → 플래그 제출 →
독립 목표 검증 → Evaluation·Evidence → 결과 → Lab 종료.

세션별 플래그를 서버 비밀에 바인딩하고
raw flag를 DB·로그·Outbox에 보관하지 않는다.
사용자 stdout이나 자체 성공 JSON으로 목표를 인정하지 않는다.

검증:
정상 소유자 접근,
합성 타인 리소스 접근의 목표 확인,
다른 Session 플래그 거절,
중복 제출·중복 결과,
oracle 비노출,
Lab 종료와 자원 회수.

UI는 이 흐름을 수행할 최소 화면만 구현하라.
실제 격리가 검증되지 않은 환경의 결과는 fake/demo로 표시하라.


원본 파일: `prompts/09-patch-grading.md`

# 9. T08 Python 패치 채점과 수정 증명 구현

SecDrill T08을 구현하라.

03-mvp-scope, 08-content-guide, 10-evaluation-evidence,
17-sandbox-isolation, 20-execution-grading을 읽어라.

첫 사건의 Python 패치 제출·검증을 연결하라.

- 허용 파일·경로·요청 크기 검사
- canonical bundle과 digest
- compile도 격리된 grading 환경에서 실행
- 학습 Lab와 grading 환경의 분리
- 외부 supervisor의 판정
- 필수 보안·우회·정상 회귀 gate
- EvaluationRevision과 근거 기록
- 공식 결과와 상세 공개 범위

검증 fixture:
참조 패치,
아무것도 수정하지 않은 패치,
모든 요청을 거절하는 패치,
한 endpoint만 고친 패치,
클라이언트 tenant 값을 신뢰하는 패치,
테스트·결과를 조작하는 패치.

필수 gate를 모두 통과해야 VERIFIED다.
플랫폼 오류는 SYSTEM_ERROR/INCONCLUSIVE로 처리한다.
숨은 입력·정답을 실패 메시지로 유출하지 않는다.


원본 파일: `prompts/10-detection-ir.md`

# 10. T10 탐지 DSL과 사고 대응 모델 구현

SecDrill T10을 구현하라.

21-detection-ir, 10-evaluation-evidence,
22-replay, 23-adaptive-randomization과 계약을 읽어라.

탐지:
제한 JSON AST, 필드 비교, window 집계,
깊이·노드·시간 제한, training/holdout 분리,
attack episode 기준 TP·FP·FN,
precision·recall·F1·FPR·latency를 구현하라.

분모0은 N/A로 처리하고 ground truth label은 규칙에 제공하지 않는다.
단순 IP·이름 암기로 통과하는지 변형 holdout으로 검사하라.

대응:
버전·seed가 고정된 순수 reducer와
문서의 네 액션을 구현하라.
피해·가용성·정상 업무·증거 보존의 변화를 기록하라.

SIMULATED, OBSERVED, USER_REPORTED를 구분한다.
모델 액션만 수행한 것을 실제 VM 조치로 표시하지 않는다.

동일 입력 재현, 액션 충돌, 정상 업무 손상,
미탐·미복구·로그 누락을 테스트하라.


원본 파일: `prompts/11-workspace.md`

# 11. T11 CTF·Wargame·Purple 작업 공간 완성

SecDrill T11을 구현하라.

05-learning-loop, 06-functional-spec, 07-ia-ux,
15-api와 현재 계약을 읽어라.

기존 API와 실제 상태를 사용해 다음을 연결하라.

- 카탈로그와 사건 상세
- CTF·Wargame·Purple 진입
- 목표·가설·메모
- 앱·터미널·코드·로그 작업 탭
- 힌트·도움 이력
- 제출·채점 진행과 실패 근거
- 단계별 산출물과 finish gate
- Lab 만료·재접속·취소 경험

UI에서 점수·권한·단계 완료를 결정하지 않는다.
CTF→Purple은 원래 Session 변경이 아닌 새 연결 Session이다.

키보드 사용, 작은 화면, 상태 알림,
출력 escaping, separate origin,
재접속 뒤 cursor 복구를 검증하라.
플랫폼 오류는 학습자 실패와 다른 안내를 제공하라.


원본 파일: `prompts/12-report-replay-skills.md`

# 12. T12 리포트·Replay·스킬·추천 구현

SecDrill T12를 구현하라.

10-evaluation-evidence, 22-replay,
23-adaptive-randomization과 관련 계약을 읽어라.

다음을 연결하라.

- 평가 차원과 evidence anchor가 있는 리포트
- 도움 수준과 판정 범위 표시
- 관측 기록과 모델 재계산의 구분
- checkpoint seek와 timeline 탐색
- 누락·만료 Artifact 표시
- taxonomy·policy 버전이 있는 skill projection
- UNKNOWN과 confidence 분리
- 이유가 설명되는 상위3개 추천
- parent·해설·힌트 노출의 전파

처음부터 재생과 checkpoint seek의 상태 digest가 같아야 한다.
플랫폼 오류·반복 풀이·상관된 증거로 역량을 과대 평가하지 않는다.
재채점은 이전 결과를 지우지 않고 새 revision으로 반영한다.

사용자가 근거를 따라가 점수와 다음 추천을 이해할 수 있는지
E2E와 fixture로 검증하라.
AI 설명은 이 단계의 필수 기능으로 추가하지 않는다.


원본 파일: `prompts/13-content-transfer.md`

# 13. T13 MVP 콘텐츠와 Transfer 완성

SecDrill T13을 구현하라.

03-mvp-scope, 08-content-guide, 09-ctf-wargame-guide,
23-adaptive-randomization을 읽어라.

MVP의 기본3개·전이3개 ScenarioVersion을 준비하라.

- 테넌트 데이터 유출
- 웹훅 재전송
- 과다 권한 API 토큰

각 사건에 정상 baseline, 취약판, 참조 수정판,
핵심 mutant, 숨은 우회·정상 회귀 검사,
힌트, 회고 질문, Transfer판을 제공하라.

각 모드의 목표와 완료 조건을 확인하고
경계 seed·무작위 seed 검증을 실행하라.
핵심 mutant는 전부 검출되어야 한다.

Transfer는 이름만 바꾼 재시도가 아니라
같은 근본 개념을 다른 사건 계열에서 검증해야 한다.
원본 해설 노출과 독립 성공을 구별하라.

독립 검수자가 플레이하지 않은 콘텐츠는
독립 검수 완료로 표시하지 않는다.
사건별 검증 보고서와 남은 품질 문제를 기록하라.


원본 파일: `prompts/14-integration-audit.md`

# 14. 기능 통합과 요구사항 누락 점검

현재 SecDrill 구현을 PRD와 MVP 범위에 대조하라.

acceptance-matrix.csv의 각 요구사항을
실제 코드·테스트·운영 절차에 연결하라.
단순 파일 존재를 기능 완료로 인정하지 않는다.

전체 흐름을 검증하라.

- CTF 완료와 Purple 연결
- Wargame 조사와 목표 증명
- Purple의 재현·탐지·대응·패치·검증·회고
- 새 사건의 Transfer
- 재접속·만료·취소·재시도
- 평가 revision·근거·스킬 갱신
- 타인 리소스 접근 차단

누락과 결함을 심각도·영향·수정 순서로 정리하라.
MVP 필수 결함을 우선 수정하고 관련 회귀 검사를 추가하라.
범위 밖 기능은 새로 구현하지 않는다.


원본 파일: `prompts/15-security-review.md`

# 15. 보안과 채점 신뢰성 집중 리뷰

현재 구현을 보안과 채점 신뢰성 관점에서 리뷰하라.

17-sandbox-isolation, 18-threat-model,
19-iam, 20-execution-grading을 기준으로 검사하라.

우선순위:
owner guard 누락,
Artifact·SSE·Replay 접근,
Lab Gateway destination 검증,
secret·oracle 노출,
compile/실행 격리,
test tampering,
result spoofing·stale fencing,
취소 후 살아 있는 자원,
quota 우회,
CSRF·출력 XSS,
서명·콘텐츠 공급망 경계.

검증 가능한 문제는 합성 로컬·staging 환경에서
실패 재현 테스트를 만들고 수정하라.
실제 외부 대상에는 공격을 수행하지 않는다.

각 발견에 위치·재현 조건·영향·수정·검증을 기록하라.
강한 runtime을 실제로 검사하지 못한 항목은
안전하다고 결론 내리지 말고 미검증으로 남겨라.


원본 파일: `prompts/16-operations-readiness.md`

# 16. T14 운영·배포·복구·개인정보 처리 준비

SecDrill T14의 운영 기반을 구현하라.

24-observability, 25-operations-deployment,
14-database, 19-iam을 읽어라.

다음을 준비하고 staging에서 검증하라.

- API·queue·Runner·Lab·grading 지표
- 개인정보 없는 구조화 로그와 tracing
- 경보와 실제 수신자 설정
- 이미지·콘텐츠 서명과 환경 분리
- CI와 staging 검증
- drain·rollback·node quarantine
- DB·Artifact 백업과 실제 복원
- 사용자 export·삭제·보관기간 sweep
- 복원 뒤 deletion tombstone 재적용
- orphan·DLQ·판정 오류 Runbook

외부 계정·비용이 필요한 작업은
구체적인 변경·비용·복구 방법을 준비한 뒤 승인을 요청하라.
승인 없이 실제 외부 배포를 수행하지 않는다.

절차 문서만 작성한 항목과 리허설한 항목을 구분하라.


원본 파일: `prompts/17-performance-chaos.md`

# 17. 성능·장애·카오스 검증

27-performance-chaos의 계획을 현재 구현에 적용하라.

먼저 실제 하드웨어·runtime·버전·자원 예약과
사용 가능한 staging 환경을 기록하라.
문서의 성능 가정을 측정 결과처럼 사용하지 않는다.

baseline→정상 부하→증가 부하→soak 순으로 측정하고,
다음 장애를 안전한 합성 환경에 주입하라.

- publish 직후 process 종료
- 실행 중 Runner 유실
- broker·DB·store 일시 장애
- 중복·역순·오래된 result
- provisioning callback 유실
- noisy neighbor와 output flood
- Control 장애 중 hard TTL

latency를 queue·provision·compile·test·ingest로 분해하라.
불변식 위반, 중단 조건, 회복 시간, orphan·DLQ 잔여를 검사하라.

재현 가능한 결과 보고서를 만들고
목표 미달의 원인을 수정한 뒤 해당 검사를 재실행하라.
운영 사용자에게 영향이 있는 실험은 승인 없이 하지 않는다.


원본 파일: `prompts/18-pilot-readiness.md`

# 18. 파일럿 출시 준비와 최종 판정

현재 SecDrill이 제한된 파일럿을 시작할 수 있는지 평가하라.

PRD·MVP·테스트 전략·운영·위협모델을 기준으로
출시 evidence dossier를 작성하라.

포함:
요구사항별 상태와 증거,
strong isolation 검증,
6개 콘텐츠 품질,
채점·중복·stale 결과 처리,
보안 발견과 잔여 위험,
성능 측정,
백업·복원·삭제 리허설,
경보 수신자,
운영 담당자,
rollback·중단 조건.

판정은 GO, CONDITIONAL GO, NO-GO 중 하나로 제안하고
각 조건과 책임자를 명확히 하라.
필수 검증의 누락을 낮은 위험으로 임의 처리하지 않는다.

10명 파일럿의 참가 조건·온보딩·관찰 항목·
학습 효과·Transfer·이탈·평가 신뢰도 수집 계획을 마련하라.
실제 사용자 성과가 없으면 아직 없다고 명시하라.

최종 배포는 실행하지 말고
승인 가능한 구체적인 배포 계획을 준비하라.


원본 파일: `prompts/19-documentation-refresh.md`

# 19. README·포트폴리오·개발 문서 현행화

실제 구현과 검증 결과에 맞게 문서를 정리하라.

32-product-readme-draft, 33-portfolio-draft,
현재 저장소와 검증 보고서를 비교하라.

README에 실제 prerequisites·시작·중지·테스트·
지원 모드·지원 콘텐츠·격리 조건·개발 상태를 기록하라.
명령은 가능하면 깨끗한 환경에서 검증하라.

포트폴리오는 문제→선택→구현→증거→한계 순으로 작성하라.
예상 성능·계획·mock 결과를 실측 성과로 쓰지 않는다.

문서와 구현이 달라진 사항은 ADR과 변경 근거를 연결하라.
초기 설계 자료를 조용히 덮어쓰지 말고
변경된 결정과 적용 범위를 명시하라.

IMPLEMENTATION_STATUS.md와 다음 backlog도 갱신하라.


원본 파일: `prompts/20-resume-work.md`

# 20. 새 대화에서 작업 이어가기

이 저장소의 SecDrill 작업을 이어가라.

AGENTS.md, CLAUDE.md,
docs/development/IMPLEMENTATION_STATUS.md,
DECISIONS_REQUIRED.md, WORKFLOW.md를 먼저 읽어라.

Git 상태와 직전 작업의 변경·검증 기록을 확인하라.
이미 완료한 작업은 반복하지 않는다.
사용자 변경과 미완료 작업을 보존하라.

현재 단계의 다음 Task와 관련 설계·계약만 추가로 읽고,
짧게 현재 상태·다음 범위·완료 기준을 설명한 뒤 진행하라.

작업 상태 문서와 실제 구현이 다르면
실제 코드·테스트 증거를 확인해 정정하라.
미검증 결과를 완료로 승격하지 않는다.


원본 파일: `prompts/21-change-or-fix.md`

# 21. 기능 변경 또는 버그 수정

SecDrill에서 다음 요청을 처리하라.

[여기에 변경 또는 버그 내용을 입력]

먼저 관련 요구사항·Task·ADR·계약·현재 구현을 확인하라.
버그이면 가능한 작은 실패 재현을 만들고 원인을 찾는다.
기능 변경이면 MVP 범위와 API·DB·event·권한·평가 영향을 정리한다.

작고 집중된 변경으로 구현하고
변경의 위험에 맞는 검증을 수행하라.
기존 사용자 기록·frozen version·평가 revision의 호환성을 확인하라.

관련 문서와 작업 상태를 갱신하고
변경·검증·남은 위험을 보고하라.
무관한 정리·새 기능·의존성 추가는 하지 않는다.


원본 파일: `prompts/22-precommit-review.md`

# 22. 커밋 전 최종 검토

현재 변경을 커밋 전에 검토하라.

Git diff와 CONTRIBUTING.md를 기준으로 다음을 확인하라.

- 변경 범위와 요구사항 일치
- 비밀·개인 설정·생성물 포함 여부
- 계약·migration·문서의 동시 수정
- 권한·실패 경로·테스트 누락
- 검증 결과의 정확성
- 서로 다른 목적이 한 변경에 섞였는지

필요한 문제를 수정하고 검증하라.
권장 커밋 단위별 파일 목록과 메시지를 제시하라.

이번 요청에서는 staging·commit·push를 실행하지 않는다.


원본 파일: `extras/first-development-request.md`

# SecDrill 최초 개발 착수 요청 예제

이 저장소에서 SecDrill 개발을 시작한다.

SecDrill-docs/README.md와 다음 문서를 먼저 읽어라:
00-common-contract.md, 02-prd.md, 03-mvp-scope.md,
11-architecture.md, 13-state-machines.md,
17-sandbox-isolation.md, 20-execution-grading.md,
30-implementation-plan.md, 31-claude-code-plan.md.

contracts와 examples도 확인하라.

먼저 문서 사이의 충돌, 구현에 필요한 미결정 사항,
MVP의 주요 위험을 정리하라.
권장 기본안을 제시하되 실행 격리·인증·외부 서비스처럼
영향이 큰 결정은 임의로 확정하지 마라.

그다음 T01 범위로 저장소 기본 구조와 계약 검증을 구현하라.
아직 전체 UI, 공격 Lab, AI 기능은 만들지 마라.
필요한 검증을 실행하고 완료·미검증·다음 작업을 기록하라.


원본 파일: `extras/short-chat-request.md`

# SecDrill 짧은 채팅 실행 요청 예제

프로젝트 지침과 IMPLEMENTATION_STATUS.md를 확인하고,
docs/development/prompts/04-async-foundation.md에 따라 T05를 진행해줘.

관련 설계와 계약을 먼저 확인하고, 구현·검증·작업 상태 갱신까지 완료해줘.
완료한 작업은 반복하지 말고, 실제 commit·push는 하지 마.


원본 파일: `extras/task-specific-examples.md`

# SecDrill 작업 단위별 추가 프롬프트 예제

이 파일은 개발 문서 `31-claude-code-plan.md`에 포함된 구체적인 작업 요청을 함께 추출한 참고 자료다. 아래 두 프롬프트는 각각 별도로 실행한다. 전체 T05/T08 프롬프트를 이미 완료했다면 같은 작업을 다시 수행하지 않는다.

## Outbox 원자 저장

```text
SecDrill T05의 첫 변경으로 submission 생성과 outbox 원자 저장을 구현한다.
00, 13, 14, 16, 30과 contracts를 먼저 읽고 관련 저장소 지침을 따른다.
기존 저장소 패턴을 확인한 뒤 최소 변경으로 구현한다.
동일 Idempotency-Key+동일 body는 같은 응답, 다른 body는409여야 한다.
커밋 이전 장애는 submission과 event 둘 다 없어야 하고,
커밋 이후 broker 장애는 outbox를 보존해야 한다.
실행 코드나 UI는 이번 변경에 추가하지 않는다.
계약·DB·장애 테스트를 수행하고 변경·검증·남은 위험을 보고한다.
```

## Python 패치 채점

```text
SecDrill T08의 Python patch grading을 구현한다.
학습 Lab와 grading VM은 분리하고 compile도 strong runtime 안에서 실행한다.
사용자 stdout과 result file을 공식 판정으로 신뢰하지 않는다.
참조 패치, 무조건 차단 패치, 한 endpoint만 고친 패치,
test framework를 바꾸는 패치를 fixture로 사용한다.
플랫폼 오류는 SYSTEM_ERROR이고 스킬에 음의 증거로 반영하지 않는다.
강한 runtime이 없으면 fake-only 개발 결과와 미검증 항목을 명시한다.
```
