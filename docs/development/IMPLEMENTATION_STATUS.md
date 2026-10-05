# 구현 상태

마지막 갱신: 2026-10-05

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**T12 리포트·Replay·스킬·추천**(프롬프트 12): 근거 anchor·도움·판정 범위가 있는 리포트와 재채점 revision, checkpoint seek Replay, `skill-v1` projection, 이유가 있는 상위 3개 추천을 구현하고 Web 화면을 붙였다. T01~T12 기반 완료. CTF·패치 결과는 격리 미검증이라 데모이며 projection에서 빠진다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| — | — | — | — | 다음: 프롬프트 13 = T13(기본·전이 콘텐츠) |

## 완료 항목

| 항목 | 검증 근거 |
|---|---|
| 저장소 규칙·Claude Code 설정·hook | 실제 세션에서 `.env` 읽기(Read·Bash `cat`) 거절, 깨진 JSON에 hook 피드백 확인 |
| 문서·계약 검사 `scripts/check.py` | `--strict` 14종 PASS |
| T01 공통 계약과 최소 실행 골격 | [T01_PLAN](T01_PLAN.md#결과) |
| T02 인증과 소유권 | [T02](T02.md#결과) |
| T05 비동기 작업 기반 | [T05](T05.md#결과). broker outage 테스트 3회 반복 통과 |
| T09 Evidence·Artifact 기반 | [T09](T09.md#결과) |
| T03 콘텐츠 출판 기반 | [T03](T03.md#결과) |
| T04·T06 Lab 수명·local-trusted 격리 | [T04_T06](T04_T06.md#결과). strong isolation 미검증 |
| T07 첫 CTF(데모) | [T07](T07.md#결과) |
| T08 Python 패치 채점(데모) | [T08](T08.md#결과) |
| T10 탐지·대응 모델 | [T10](T10.md#결과) |
| T11 작업 공간 | [T11](T11.md#결과) |
| T12 리포트·Replay·스킬 | [T12](T12.md#결과). Playwright 25건, `./gradlew clean check` 185건 통과(skip 0) |

## 미검증 항목

- GitHub CI(`check`·`build` job) 실행: push하지 않음. Linux 호스트에서의 Testcontainers 동작 포함
- 실제 OIDC provider와 브라우저 cookie 동작(D-09 보류)
- ask 규칙(commit·push 등)과 강제 push deny의 실제 동작: 외부 영향이 있어 시험하지 않음
- 개인정보 삭제 실행(전용 역할·함수·승인·통합 테스트): 미구현, FR-10 게이트 미통과([검토](PRIVACY_ERASURE_REVIEW.md))
- 배포 런타임의 `control_app` 역할 접속(D-14), S3 호환 Artifact store(D-15), purge·orphan sweeper
- 다중 인스턴스·broker cluster·부하: T14·T17 범위
- strong runtime(`lab-strong`) 격리 전체: KVM 없는 호스트라 실행 불가(D-10). local-trusted 결과는 강한 격리 증거가 아니다
- rootless Docker daemon, Gateway→runner network 경로, 터미널 websocket, runner quarantine, mTLS(D-17), 이미지 registry 서명(D-16)
- grading-strong runtime, 채점 감지형 패치, 학습자용 최소 반례 설명(T08 남은 것)
- 콘텐츠 runtime verifier(출판 게이트의 참조 해답·mutant 자동 실행): 없음. 그 전에는 local profile에서도 콘텐츠를 출판할 수 없어 UI 전체 흐름을 브라우저로 검증하지 못했다
- CTF 관측이 guest 안 기록에 의존(ADR 0008), 로컬 runner·Gateway 실행 진입점 없음, 최종 리포트(T12)·힌트·점수
- 성능·카오스: 해당 구현 없음

## Blocker

- **strong runtime 실행 호스트 없음(D-10)**: 학습자 공격 Lab 공개·외부 파일럿과 T06 완료 판정을 막는다. 내부 개발(T07 등)은 local-trusted로 계속할 수 있다.

## 다음 작업

1. 프롬프트 13 = T13(기본 3 + 전이 3 콘텐츠, seed suite·독립 검수)
2. D-10 결정: KVM 지원 Linux runner host와 strong runtime 선택 후 같은 격리 테스트 실행
3. 자원 API가 생길 때마다 owner guard 연결([T02 후속](T02.md#후속-task가-반드시-연결할-것))
