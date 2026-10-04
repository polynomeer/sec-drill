# 구현 상태

마지막 갱신: 2026-10-05

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**T03 콘텐츠 저작·검증·출판 기반**(프롬프트 06): 기반 구현·로컬 검증 완료. 실제 runtime verifier가 없어 기본 설정에서는 어떤 번들도 출판되지 않는다. T01·T02·T05·T09 기반 완료(개인정보 삭제 실행은 미구현). 채점은 fake worker만 있고 Session·Lab API, 실제 채점, 공격 Lab은 시작하지 않았다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| — | — | — | — | 다음: 프롬프트 07 = T04·T06(Lab과 격리) |

## 완료 항목

| 항목 | 검증 근거 |
|---|---|
| 저장소 규칙·Claude Code 설정·hook | 실제 세션에서 `.env` 읽기(Read·Bash `cat`) 거절, 깨진 JSON에 hook 피드백 확인 |
| 문서·계약 검사 `scripts/check.py` | `--strict` 14종 PASS |
| T01 공통 계약과 최소 실행 골격 | [T01_PLAN](T01_PLAN.md#결과) |
| T02 인증과 소유권 | [T02](T02.md#결과) |
| T05 비동기 작업 기반 | [T05](T05.md#결과). broker outage 테스트 3회 반복 통과 |
| T09 Evidence·Artifact 기반 | [T09](T09.md#결과) |
| T03 콘텐츠 출판 기반 | [T03](T03.md#결과). `./gradlew clean check` 111건 통과(skip 0) |

## 미검증 항목

- GitHub CI(`check`·`build` job) 실행: push하지 않음. Linux 호스트에서의 Testcontainers 동작 포함
- 실제 OIDC provider와 브라우저 cookie 동작(D-09 보류)
- ask 규칙(commit·push 등)과 강제 push deny의 실제 동작: 외부 영향이 있어 시험하지 않음
- 개인정보 삭제 실행(전용 역할·함수·승인·통합 테스트): 미구현, FR-10 게이트 미통과([검토](PRIVACY_ERASURE_REVIEW.md))
- 배포 런타임의 `control_app` 역할 접속(D-14), S3 호환 Artifact store(D-15), purge·orphan sweeper
- 다중 인스턴스·broker cluster·부하: T14·T17 범위
- strong runtime 격리, 콘텐츠 runtime 검증(참조 해답·mutant·seed), 이미지 registry 서명: T06·T08
- 성능·카오스: 해당 구현 없음

## Blocker

없음.

## 다음 작업

1. 프롬프트 07 = T04·T06(Session·Lab desired state·quota·gateway, strong runtime Agent)
2. 자원 API가 생길 때마다 owner guard 연결([T02 후속](T02.md#후속-task가-반드시-연결할-것))
