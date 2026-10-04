# SecDrill 구현 계획

계약과 신뢰 경계를 먼저 고정하고 한 사건의 수직 기능을 만든 뒤 모드·콘텐츠를 확장한다. 아래 task는 코드뿐 아니라 검증 증거를 완료 조건으로 가진다.

## 저장소 제안

`web/`, `control-plane/{identity,catalog,session,submission,evaluation,evidence,recommendation,operations,app}`, `execution/{protocol,orchestrator,agent}`, `simulator/`, `content/{public,private,validation}`, `contracts/`, `deploy/`, `scripts/`, `docs/`로 구성한다. hidden content는 learner web build context 밖에 둔다. Agent/Orchestrator 의존성 검사에서 JDBC·Control repositories를 금지한다.

| Task | 의존 | 구현 | 완료 증거 |
|---|---|---|---|
| T01 | 없음 | 공통 enum·JSON/OpenAPI validator·DB migrations | contract parse, SQL integration |
| T02 | T01 | OIDC·opaque session·owner guard·CSRF | cross-owner·logout·refresh reuse tests |
| T03 | T01 | Scenario bundle·검증·서명·publish | reference/mutant와 승인 분리 |
| T04 | T01,T02 | Session·Lab desired state·quota·gateway | duplicate 생성·취소 경합·별도 origin |
| T05 | T01 | Outbox·inbox·job claim·heartbeat·fencing | kill·duplicate·late result 검사 |
| T06 | T04,T05 | strong runtime Agent·network·TTL | adversarial network·resource·cleanup |
| T07 | T03,T05,T06 | CTF 목표 verifier·flag | session binding·oracle 비노출 |
| T08 | T03,T05,T06 | Python patch adapter·hidden/normal gates | tamper·bypass·all-deny mutant 검출 |
| T09 | T01,T05 | Ledger·hash·artifact policy | concurrent append·gap·delete tombstone |
| T10 | T03,T09 | detection DSL·holdout·IR reducer | ground truth·N/A·action tradeoff fixtures |
| T11 | T07~T10 | Web CTF/Wargame/Purple workspace | keyboard E2E·reconnect·진행물 보존 |
| T12 | T09,T10,T11 | report·Replay·skill·추천 | seek parity·UNKNOWN·노출 전파 |
| T13 | T03,T07,T08,T10 | 기본3+전이3 콘텐츠 | seed suite·독립 검수 |
| T14 | 전부 | 배포·관측·backup·chaos·pilot | 출시 evidence dossier |

## 작업 크기와 리뷰

각 task를 0.5~2일의 검토 가능한 변경으로 나눈다. T06은 network policy, runtime lifecycle, quota reconciliation, cleanup을 각각 분리한다. PR은 문제·선택·계약 영향·테스트·운영 rollback을 설명하고 구현하지 않은 기능을 완료로 표시하지 않는다.

## Definition of Done

요구사항 ID 연결, API/event/DB 호환, 실패 경로, 권한 검사, 관측·audit, 적절한 자동 검증, 문서 업데이트가 필요하다. 코드만 존재하거나 mock happy path만 통과한 강한 격리 task는 Done이 아니다. 성능 목표는 실제 host 측정 전 미검증 상태로 유지한다.

## 최초 수직 시연

한 learner 로그인 → tenant leak CTF 시작 → Lab 생성 → 합성 타인 주문 접근 → 플래그 제출 → independent verifier → append evidence → report → Lab 종료를 먼저 만든다. 이어서 같은 사건 Purple patch가 all-deny·single-route mutant를 구분하는지 확인한다. 두 시연이 안정된 후 추천·다른 사건·optional AI를 붙인다.
