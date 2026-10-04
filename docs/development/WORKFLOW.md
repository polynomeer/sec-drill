# 개발 작업 흐름

모든 에이전트와 기여자가 따르는 작업 순서다. 규칙은 [AGENTS.md](../../AGENTS.md), 커밋·리뷰는 [CONTRIBUTING](../../CONTRIBUTING.md)에 있다.

## 새 대화에서 읽을 최소 파일

1. [AGENTS.md](../../AGENTS.md): 공통 규칙(Claude Code는 `CLAUDE.md`로 자동 로드)
2. [IMPLEMENTATION_STATUS](IMPLEMENTATION_STATUS.md): 현재 단계, 활성 Task, blocker
3. [DECISIONS_REQUIRED](DECISIONS_REQUIRED.md): 작업에 걸린 결정
4. 활성 Task의 계획 문서(예: [T01_PLAN](T01_PLAN.md))
5. 해당 Task가 지정한 설계 문서와 계약만. 공통 계약 [00](../../SecDrill-docs/docs/00-common-contract.md)은 항상 포함한다.

`ALL-IN-ONE.md`나 문서 전체를 한 번에 읽지 않는다. 필요한 개별 문서만 연다.

## 단계

1. **문서 확인**: 관련 요구사항 ID, Task, ADR, 계약을 찾는다. 문서가 제안·가정인지 확정 요구인지 [DESIGN_BASELINE](DESIGN_BASELINE.md)의 분류로 구분한다.
2. **범위 설정**: 이번 변경의 목표, 하지 않을 일, 완료 기준, 검증 방법을 먼저 적는다. 결정이 필요하면 DECISIONS_REQUIRED에 추가하고 권장안을 제시한다. 사소한 선택은 기존 패턴을 따라 진행하고 보고에 남긴다.
3. **구현**: 기존 패턴을 확인하고 작은 단위로 변경한다. 계약 변경은 문서·schema·테스트·추적표를 같은 변경에 포함한다.
4. **검증**: 실패 경로 테스트를 포함해 실행한다. `.venv/bin/python scripts/check.py`는 항상 실행한다. 실행하지 못한 검증은 이유와 함께 미검증으로 기록한다. 검사 범위는 [VERIFICATION](VERIFICATION.md)에 있다.
5. **리뷰**: diff를 스스로 다시 읽고 CONTRIBUTING의 리뷰 항목을 확인한다. 아래 필수 검토 대상이면 해당 관점을 명시적으로 점검한다.
6. **상태 갱신**: IMPLEMENTATION_STATUS의 해당 항목만 현재 상태로 고친다. 대화 기록이나 작업 일지를 누적하지 않는다.

## 작업 완료 기준

다음을 모두 만족해야 Done이다(`30` Definition of Done 기반).

- 요구사항 ID와 Task가 연결되어 있다.
- API·이벤트·DB 계약과 호환되거나, 변경 시 문서·schema·테스트가 함께 개정되었다.
- 성공 경로와 실패 경로(권한 거부, 중복, 경합, 오래된 결과, 플랫폼 오류)가 자동 테스트로 검증되었다.
- 권한 검사와 audit가 필요한 곳에 있다.
- 실제 환경이 필요한 검증(PostgreSQL, strong runtime, 성능)을 실행했거나 미검증으로 명시했다. fake·mock 성공만으로 격리 Task를 Done으로 표시하지 않는다.
- `scripts/check.py`가 통과하고 IMPLEMENTATION_STATUS가 갱신되었다.

## 필수 검토 대상

다음 변경은 리뷰에서 해당 관점을 반드시 확인하고 PR 본문에 결과를 적는다.

| 변경 | 확인할 것 |
|---|---|
| 계약(OpenAPI·event schema·DDL·enum) | 00과 enum 일치, 하위 호환(추가 필드 optional, breaking은 새 major), 관련 문서·추적표 개정, `scripts/check.py` 통과 |
| 상태 머신·전이 | 13과의 일치, terminal 상태 역전이 금지, version CAS, 동시 요청 중 하나만 성공, 늦은 callback 처리 |
| 권한·소유권·인증 | 타인 자원 404, 운영자 접근 audit, CSRF·Origin, owner 범위 FK·repository scope |
| 실행·채점 | 플랫폼 오류가 SYSTEM_ERROR/INCONCLUSIVE로 남는지, fencing token, Oracle 비노출, 사용자 출력 불신 |
| Ledger·개인정보 | append-only, seq 연속성, 삭제 tombstone, 비밀·PII 미기록 |

## 팩 문서를 수정할 때

1. 개별 문서(`SecDrill-docs/docs/`, `contracts/`, `examples/`)만 수정한다. 생성물 3종은 직접 고치지 않는다.
2. `.venv/bin/python scripts/check.py`를 실행한다. manifest 불일치는 정상적인 중간 상태다.
3. 생성물 재생성은 D-07 결정 후 그 절차를 따른다. 결정 전에는 팩 내부 `.DS_Store`를 제거한 뒤 `.venv/bin/python SecDrill-docs/tools/build_pack.py`를 실행한다. 저장소 루트에 생기는 ZIP은 `.gitignore`로 제외된다.
4. 재생성된 ALL-IN-ONE·VALIDATION·MANIFEST의 diff를 확인하고 같은 커밋에 포함한다.

## 여러 에이전트를 함께 사용할 때

Claude Code와 Codex는 모두 AGENTS.md를 따른다(Claude Code는 CLAUDE.md의 import로 읽는다). 소유권은 Task 단위로 정한다.

- **소유권 선언**: 작업 시작 시 IMPLEMENTATION_STATUS의 "활성 Task" 표에 Task, 담당 에이전트, 대상 경로, 시작일을 적는다. 한 Task(또는 경로)에는 한 담당자만 둔다.
- **공유 계약은 단일 소유**: 공통 enum, `contracts/`, migrations, job protocol은 동시에 두 에이전트가 고치지 않는다. 다른 Task가 변경이 필요하면 소유 Task에 요청을 남긴다.
- **작업 공간 분리**: 같은 작업 트리에서 두 에이전트를 동시에 실행하지 않는다. 병렬 작업은 사용자가 만든 별도 브랜치·worktree에서 한다. 에이전트가 스스로 브랜치를 만들지 않는다.
- **인계**: 작업을 넘길 때 IMPLEMENTATION_STATUS의 해당 행에 다음을 적고 담당을 비운다. 변경 파일, 실행한 검증과 결과, 미검증·실패 항목, 남은 작업, 주의할 결정. 받는 쪽은 `git status`와 diff로 실제 상태를 먼저 확인하고, 기록과 다르면 실제 상태를 따른 뒤 차이를 보고한다.
- **검증 독립성**: 한 에이전트가 구현한 보안·채점·격리 변경은 가능하면 다른 에이전트나 사람이 리뷰한다. 요약 보고만으로 승인하지 않고 테스트 결과와 diff를 확인한다.
