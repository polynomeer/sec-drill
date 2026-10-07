# 구현 상태

마지막 갱신: 2026-10-07

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**T16 운영·배포·복구·개인정보 준비**(프롬프트 16): 범위 중 로컬에서 완전히 리허설 가능한 **개인정보 데이터주체 실행**(GAP-2/FR-10)을 먼저 구현·리허설했다([T16](T16.md), [ADR 0013](../adr/0013-privacy-data-subject-execution.md)). export·삭제 요청/2인 승인/전용 `privacy_eraser` 역할의 `SECURITY DEFINER` 삭제 실행·retention/orphan sweep·tombstone 재적용을 통합 테스트로 확인했다(원장 row 유지, hash chain 보존). 이어서 **운영 통제(GAP-1)**인 Lab pool drain·Runner quarantine(`lab_pool`·`runner_quarantine`, V12)과 **관측**(Micrometer 운영 게이지·`X-Request-Id` 상관 id)을 구현·리허설했다. 남은 것(경보 수신자·이미지 서명/환경 분리·CI/staging·백업/복원·DLQ 재처리·runbook)은 staging·cloud·외부 수신자가 있어야 리허설이 성립하므로 절차 문서로 남긴다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| — | — | — | — | 다음: T16 절차 문서(경보·배포·백업·runbook) |

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
| T12 리포트·Replay·스킬 | [T12](T12.md#결과) |
| T13 MVP 콘텐츠·Transfer(데모) | [T13](T13.md), [보고서](content-verification.md). 6 사건 참조 VERIFIED·핵심 mutant 검출 |
| T14 통합·요구사항 점검 | [T14](T14.md). FR/NFR 대조, 제출 채점 job 결함 수정. `./gradlew clean check` 193건 통과(skip 0) |
| T15 보안·채점 신뢰성 리뷰 | [T15](T15.md). 11개 우선순위 영역 정적 리뷰+적대 테스트 확인. 악용 가능 결함 없음, 코드 변경 없음. SEC-1(holdout gaming) 권고 보류 |
| T16 개인정보 데이터주체 실행 | [T16](T16.md), [ADR 0013](../adr/0013-privacy-data-subject-execution.md). export·삭제 실행·retention/orphan sweep·tombstone 재적용. `PrivacyExecutionTest`·`PrivacyPrivilegeTest` 9건 통과. FR-10 게이트 충족(원장 row 유지 모델) |
| T16 운영 통제(GAP-1) | [T16](T16.md). Lab pool drain·Runner quarantine(claim 차단·결과 STALE). `lab_pool`·`runner_quarantine`(V12). `OpsControlsTest` 4건 통과, 채점·lease 회귀 없음 |
| T16 관측(24) | [T16](T16.md). Micrometer 운영 게이지(outbox·lab·grading·runner)+`X-Request-Id` 상관 id. `ObservabilityTest` 3건 통과. 스크래프 노출·JSON 로그 인코더는 배포 절차 |

## 미검증 항목

- GitHub CI(`check`·`build` job) 실행: push하지 않음. Linux 호스트에서의 Testcontainers 동작 포함
- 실제 OIDC provider와 브라우저 cookie 동작(D-09 보류)
- ask 규칙(commit·push 등)과 강제 push deny의 실제 동작: 외부 영향이 있어 시험하지 않음
- 개인정보 삭제 실행: 구현·리허설 완료(T16, 원장 row 유지 모델). 배포 전용 `privacy_eraser` 접속(D-14)·진짜 재인증(D-09)·만료 세션 전체 원장 삭제는 미완([검토](PRIVACY_ERASURE_REVIEW.md))
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

1. T16 남은 범위([T16](T16.md), 절차 문서): 경보 규칙·수신자, 이미지 서명·환경 분리, CI/staging, 백업/복원 리허설, DLQ 재처리·runbook, JSON 로그 인코더(배포 설정)
2. SEC-1 결정([T15](T15.md)): 탐지 holdout 피드백 거칠게 하기 / 제출 quota — 평가 계약·ADR·테스트 동반 변경
3. GAP-3(Wargame objective verifier)
4. D-10 결정: KVM 지원 Linux runner host와 strong runtime 선택 후 doc 17 출시 검증 체크리스트 실행, T15 미검증 항목 재판정
5. 자원 API가 생길 때마다 owner guard 연결([T02 후속](T02.md#후속-task가-반드시-연결할-것))
