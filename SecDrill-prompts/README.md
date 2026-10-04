# SecDrill 개발 프롬프트 모음

작성일: 2026-10-04. 이 문서 묶음은 대화에서 제공한 초기화 프롬프트, 후속 프롬프트 22개, 최초 착수·짧은 실행 요청 예제를 파일로 추출한 것이다. 제품 구현이나 프롬프트 실행 결과를 포함하지 않는다.

## 사용 방법

새 개발 저장소에 `SecDrill-docs/`를 먼저 옮긴다. 이 묶음의 `prompts/` 안에 있는 파일들은 해당 저장소의 `docs/development/prompts/`에 복사한다. 원래 설계 문서는 `SecDrill-docs/`에 유지한다. 아래 예시는 복사가 끝난 뒤 개발 저장소 루트에서 사용하는 경로다.

```text
secdrill/
├── SecDrill-docs/
└── docs/
    └── development/
        └── prompts/
            ├── 00-project-initialization.md
            ├── 01-initialization-review.md
            ├── 02-contract-foundation.md
            ├── 04-async-foundation.md
            └── ...
```

초기화에는 00번을 사용한다. 후속 개발은 01~19번을 한 단계씩 실행하고, 20~22번은 작업 재개·변경·커밋 전 검토 때 사용한다. 번호는 앞서 대화에서 제시한 후속 프롬프트 번호를 유지했다. Task ID와 프롬프트 번호는 다르다. 예를 들어 04번은 T05다.

긴 규칙은 AGENTS.md·CLAUDE.md와 이 파일들에 두고 채팅에서는 해당 파일과 현재 작업을 지정한다. 모든 프롬프트를 한 번에 실행하도록 요청하지 않는다. 다음 단계의 전제는 앞 단계의 완료 보고뿐 아니라 실제 검증 증거다. 실제 runtime·외부 계정·운영 담당이 필요한 검증은 미검증으로 기록한다.

`ALL-PROMPTS.md`는 전체를 한 파일에서 읽기 위한 통합본이다. 개별 파일이 단일 출처다. 수정 후 `tools/build_pack.py`를 실행하면 통합본·검증 기록·무결성 목록·ZIP을 다시 만든다. 표준 Python만 사용한다.

## 프롬프트 목록

| 번호 | 프롬프트 | 사용 시점 |
|---|---|---|
| 00 | [초기화와 개발 규칙](prompts/00-project-initialization.md) | 제품 구현 시작 전 |
| 01 | [초기화 결과 검토](prompts/01-initialization-review.md) | 준비 결과와 시작 조건 확인 |
| 02 | [T01 공통 계약과 실행 골격](prompts/02-contract-foundation.md) | 첫 구현 |
| 03 | [T02 인증과 소유권](prompts/03-identity-ownership.md) | 사용자 경계 |
| 04 | [T05 비동기 작업 기반](prompts/04-async-foundation.md) | Outbox·lease·fencing |
| 05 | [T09 Evidence와 Artifact](prompts/05-evidence-artifacts.md) | 증거·저장 기반 |
| 06 | [T03 콘텐츠 출판 기반](prompts/06-content-publishing.md) | 저작·검증·서명 |
| 07 | [T04와 T06 Lab와 격리](prompts/07-lab-isolation.md) | 실제 실행 경계 |
| 08 | [T07 첫 CTF 수직 기능](prompts/08-first-ctf.md) | 첫 사건 완결 |
| 09 | [T08 패치 채점](prompts/09-patch-grading.md) | 수정 증명 |
| 10 | [T10 탐지와 대응](prompts/10-detection-ir.md) | Purple 기반 |
| 11 | [T11 작업 공간](prompts/11-workspace.md) | 모드별 사용자 흐름 |
| 12 | [T12 리포트와 학습 결과](prompts/12-report-replay-skills.md) | Replay·스킬·추천 |
| 13 | [T13 콘텐츠와 Transfer](prompts/13-content-transfer.md) | MVP 6개 버전 |
| 14 | [통합과 누락 점검](prompts/14-integration-audit.md) | 요구사항 대조 |
| 15 | [보안과 채점 신뢰성 리뷰](prompts/15-security-review.md) | 위험 집중 검토 |
| 16 | [T14 운영 준비](prompts/16-operations-readiness.md) | 배포·복구·삭제 |
| 17 | [성능과 카오스 검증](prompts/17-performance-chaos.md) | 실측과 장애 시험 |
| 18 | [파일럿 출시 판정](prompts/18-pilot-readiness.md) | 승인 자료 준비 |
| 19 | [README와 포트폴리오 현행화](prompts/19-documentation-refresh.md) | 실제 구현 반영 |
| 20 | [새 대화에서 이어가기](prompts/20-resume-work.md) | 작업 재개 |
| 21 | [기능 변경과 버그 수정](prompts/21-change-or-fix.md) | 반복 사용 |
| 22 | [커밋 전 검토](prompts/22-precommit-review.md) | staging·commit 전 |

## 추가 예제

- [최초 착수 요청](extras/first-development-request.md): 초기화와 후속 절차를 대체하지 않는 간단한 시작 요청. 02번과 범위가 겹치므로 이미 T01을 수행했다면 다시 실행하지 않는다.
- [짧은 채팅 실행 요청](extras/short-chat-request.md): 긴 지침을 파일에서 읽게 하는 요청 예제.
- [커밋 메시지·PRD 참고 예제](extras/task-specific-examples.md): 개발 문서에서 제공된 Outbox·채점 작업의 구체적인 요청 예제.

## 실행 경계

프롬프트는 작업 지침이며 자동 실행 스크립트가 아니다. 기존 사용자 변경을 보존하고, commit·push·배포는 각각 사용자 요청을 따른다. 프롬프트 파일에 실제 secret·개인 데이터·외부 공격 대상을 적지 않는다. Claude Code 설정 문법은 설치 버전과 공식 문서로 확인하도록 초기화 지침에 명시했다.
