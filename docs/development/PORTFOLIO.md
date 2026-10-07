# SecDrill 포트폴리오

작성일: 2026-10-07. 이 문서는 **실제 구현된 것과 검증된 것만** 증거로 든다. 예상 성능·계획·mock을 실측 성과로 쓰지 않는다. 모든 실행 결과는 현재 **데모**(강한 격리 미검증, D-10)이며, 성능·실사용 성과는 **아직 측정하지 않았다.** 설계 초안은 [33-portfolio-draft](../../SecDrill-docs/docs/33-portfolio-draft.md)이고, 이 문서가 그 초안을 실제 상태로 대체한다.

## 문제

플래그 획득이나 패치 제출만으로는 보안 실무 역량의 일부만 드러난다. SecDrill은 취약점 **발견→재현→탐지→대응→수정→재검증**을 하나의 사건으로 연결하고, 다른 사건(Transfer)에서 같은 개념을 독립적으로 다시 풀 수 있는지로 학습 전이를 확인하려 한다. 어려운 점은 (1) 사용자 코드·공격 Lab을 안전하게 실행하고, (2) 자기 주장·stdout·숨은 테스트 변조를 신뢰하지 않고 채점하며, (3) 중복·오래된·역순 결과에도 평가가 하나로 수렴하고, (4) 점수를 역량으로 착각하지 않게 근거를 남기는 것이다.

## 핵심 설계 선택

1. **CTF와 Purple이 같은 사건 버전을 공유하되 진입점을 분리** — 빠른 성취와 종합 학습을 함께 주고 플래그 순위를 역량으로 오해하지 않게 한다.
2. **채점을 신뢰 경계 밖 supervisor로** — FLAG는 세션에 묶인 서명 receipt + runner의 서버측 관측으로 **서버가** verdict를 계산하고, PATCH는 job마다 새 환경에서 compile + 별도 supervisor의 숨은 테스트로 판정한다. app의 self-declared 결과를 신뢰하지 않는다([ADR 0008](../adr/0008-ctf-flags-objective-observation-and-demo-results.md)·[0009](../adr/0009-python-patch-grading-and-supervisor.md)).
3. **정확히-한-번 전달을 가정하지 않고 정합성을 강제** — Outbox + job lease + fencing token + DB unique로 중복·stale·역순을 흡수하고 active evaluation을 하나로 유지한다([ADR 0004](../adr/0004-outbox-rabbitmq-and-job-leases.md)).
4. **Evidence Ledger를 원본으로, Replay·스킬을 파생** — 관측(OBSERVED)·사용자 보고(USER_REPORTED)·시뮬레이션(SIMULATED)·서버 검증(SERVER_VERIFIED)을 구분하고, 근거가 부족하면 UNKNOWN으로 둔다([ADR 0003](../adr/0003-canonical-digest-and-evidence-hash.md)·[0012](../adr/0012-reports-replay-skills-recommendations.md)).
5. **강한 격리가 없으면 fail closed** — local-trusted는 개발 전용임을 코드·결과·보고에 명시하고 결과를 demo로 표시하며, 강한 runtime 요구를 낮추는 fallback을 만들지 않는다([ADR 0007](../adr/0007-lab-lifecycle-local-trusted-runtime-and-gateway.md)).

스택: Kotlin/Spring Boot 모듈러 모놀리스(Control Plane) + 별도 신뢰 경계(Orchestrator·Runner·Lab Gateway), PostgreSQL(Flyway V1~V12), RabbitMQ, React+Vite. 실제 구현에서 내린 결정은 [ADR 0001~0013](../adr/README.md)에 기록했고, 초기 설계와 달라진 부분은 각 ADR의 "원 초안/비교한 대안"에 연결했다(조용히 덮어쓰지 않음).

## 구현과 증거 (데모 프로파일)

증거는 저장소의 통과 테스트와 검증 보고서다. 아래는 재현 가능하다(`./gradlew check`, 개별 테스트 클래스).

| 선택 | 구현 | 증거 |
|---|---|---|
| 세 모드·Purple 전 과정 | 카탈로그·Session·Lab·Gateway·탐지·대응·패치·회고·리포트 | `CtfFlowTest`, `ResponseDrillTest`, `PatchGradingTest`, `InsightTest` |
| 신뢰 경계 밖 채점 | 서명 receipt + 서버 관측, 외부 supervisor, 서버 계산 verdict | `CtfGradingTest`, `PatchGradingTest`; FLAG 위조·cross-session 거절 |
| 중복·stale·역순 수렴 | Outbox·lease·fencing·unique | `JobLeaseTest`(fencing·stale·SYSTEM_ERROR), `OutboxDeliveryTest`(duplicate once·DLQ), `CrashBeforeCommitTest` |
| Evidence Ledger 정합 | append-only·hash chain·trust level·seek parity | `EvidenceLedgerTest`(tamper 검출·seq·시크릿 거절), `InsightTest`(Replay seek·owner) |
| 콘텐츠 품질 | 6개 사건 참조 VERIFIED·mutant 검출 | [content-verification](content-verification.md), `ContentSuiteTest`, `DetectionSeedSuiteTest`(데모) |
| 접근제어·보안 | owner guard 전수, 11개 영역 리뷰 | `OwnershipGuardTest`, [T15](T15.md)(악용 가능 결함 없음) |
| 개인정보·운영 | export·삭제 실행·tombstone·drain·quarantine·지표 | [T16](T16.md), `PrivacyExecutionTest`·`PrivacyPrivilegeTest`·`OpsControlsTest`·`ObservabilityTest` |

시연 흐름(데모): 테넌트 주문 접근 재현→CTF 목표→탐지 규칙(토큰 회수의 정상 업무 손상 관측)→무조건 차단 패치는 보안은 막아도 회귀 실패로 NOT_VERIFIED→근본 권한 수정은 통과→송장 전이판에서 도움 없이 재검증→리포트 증거 anchor 재생. 전부 `local-trusted` demo다.

## 한계와 다음 단계

- **강한 격리 미검증(D-10)**: KVM 없는 호스트라 microVM(`lab-strong`/`grading-strong`)을 실행하지 못한다. 모든 결과는 demo이고 격리·성능 증거가 아니다. 외부 공격 Lab은 공개하지 않는다.
- **성능·실사용 성과 없음**: 지정 하드웨어·staging 부재로 부하·soak·지연 목표를 측정하지 않았다([T17](T17.md)). 파일럿 미실행이라 학습 효과·Transfer·이탈·평가 신뢰도 데이터는 **아직 없다.**
- **출판 불가**: runtime verifier가 없어 콘텐츠 출판 게이트가 INCOMPLETE다.
- **파일럿 판정 NO-GO**([T18](T18.md)): 강한 격리·경보 수신자·운영 담당자·백업 복원 리허설·성능 측정이 조건(B1~B7)이다.
- **다음**: 강한 runtime host 선택·17 출시 검증, 콘텐츠 runtime verifier, 지정 하드웨어 성능 측정, 백업·복원 리허설, 담당자·수신자 배정 후 10명 제한 파일럿. 조직·대회·AI 설명은 핵심 결과가 신뢰 가능해진 뒤로 미룬다.
