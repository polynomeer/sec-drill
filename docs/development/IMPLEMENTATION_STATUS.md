# 구현 상태

마지막 갱신: 2026-10-04

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**Phase 0: 저장소·운영 기반 준비** (완료, 초기 커밋 대기). 제품 코드와 공격 Lab 구현은 시작하지 않았다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| — | — | — | — | 다음 작업은 T01([T01_PLAN](T01_PLAN.md)) |

## 완료 항목

| 항목 | 검증 근거 |
|---|---|
| 저장소 규칙: AGENTS.md, CLAUDE.md(`@AGENTS.md` import), CONTRIBUTING.md, PR 템플릿 | 문서 작성. `scripts/check.py` 링크 검사 통과 |
| Claude Code 프로젝트 설정 `.claude/settings.json` | Claude Code 2.1.231 기준 공식 문서(settings·permissions·hooks·memory)로 형식 확인. `scripts/check.py` 설정 검사 통과 |
| PostToolUse hook(JSON 문법 검사) | 유효 JSON exit 0, 깨진 JSON exit 2와 오류 메시지, 비대상 파일 exit 0을 수동 입력으로 확인 |
| `.gitignore`, `.editorconfig` | `git check-ignore` 기반 샘플 22건 검사 통과 |
| 검증 환경: `.venv` + `requirements-dev.txt` 고정 버전 | Python 3.14.2에서 설치, `build_pack.validate()` 결과가 제공된 VALIDATION.json과 동일 |
| `scripts/check.py`(read-only 검사 9종) | 전부 PASS. 검사별 범위는 [VERIFICATION](VERIFICATION.md) |
| 설계 기준선 검토 | [DESIGN_BASELINE](DESIGN_BASELINE.md) 발견 사항 F-01~F-13 |

## 미검증 항목

- CI 워크플로의 GitHub 실행(push하지 않음)
- 새 세션에서 Claude Code가 deny 규칙·hook·`@AGENTS.md` import를 실제로 적용하는지(설정 형식만 확인함)
- 실제 PostgreSQL에서의 DDL 적용·제약 동작(구문 분석만 수행)
- 제품 build·테스트, strong runtime 격리, 성능·카오스: 해당 구현 없음

## Blocker

- T01 착수 전 결정: D-01, D-02, D-03([DECISIONS_REQUIRED](DECISIONS_REQUIRED.md))

## 다음 작업

1. 초기 커밋(사용자 요청 시)
2. D-01~D-03 결정
3. T01-1부터 진행
