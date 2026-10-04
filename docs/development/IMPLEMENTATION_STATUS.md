# 구현 상태

마지막 갱신: 2026-10-04

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**T02 인증과 소유권**(프롬프트 03): 로컬 구현·검증 완료. T01(프롬프트 02)은 커밋 `a95f604`까지 완료. 제품 기능(Session·Lab·제출·채점)과 공격 Lab은 시작하지 않았다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| — | — | — | — | 다음: 프롬프트 04 = T05(비동기 작업 기반). D-04 결정 필요 |

## 완료 항목

| 항목 | 검증 근거 |
|---|---|
| 저장소 규칙·Claude Code 설정·hook | 실제 세션에서 `.env` 읽기(Read·Bash `cat`) 거절, 깨진 JSON에 hook 피드백 확인 |
| 문서·계약 검사 `scripts/check.py` | `--strict` 12종 PASS |
| T01 공통 계약과 최소 실행 골격 | [T01_PLAN](T01_PLAN.md#결과) |
| T02 인증과 소유권 | [T02](T02.md#결과). `./gradlew clean check` 56건 통과(skip 0) |

## 미검증 항목

- GitHub CI(`check`·`build` job) 실행: push하지 않음. Linux 호스트에서의 Testcontainers 동작 포함
- 실제 OIDC provider와 브라우저 cookie 동작(D-09 보류)
- ask 규칙(commit·push 등)과 강제 push deny의 실제 동작: 외부 영향이 있어 시험하지 않음
- 동시성 경합(CAS·head lock), 개인정보 삭제 함수: T05·T09·T14 범위
- strong runtime 격리, 성능·카오스: 해당 구현 없음

## Blocker

- T05 착수 전 D-04(canonical digest와 hash chain) 결정

## 다음 작업

1. D-04 결정
2. 프롬프트 04 = T05(Outbox·inbox·job claim·heartbeat·fencing)
3. 자원 API가 생길 때마다 owner guard 연결([T02 후속](T02.md#후속-task가-반드시-연결할-것))
