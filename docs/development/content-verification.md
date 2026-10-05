# T13 콘텐츠 검증 보고서

마지막 갱신: 2026-10-05. 대상: MVP 기본 3개 + 전이 3개 사건(총 6 ScenarioVersion). 모든 결과는 **격리 미검증 local-trusted 채점의 데모**이며, **독립 검수자 플레이·실제 출판은 미완**이다. strong runtime verifier가 없어(D-10) 이 콘텐츠는 출판 게이트를 통과하지 못한다(INCOMPLETE).

## 사건별 결과

| 사건(계열) | lab | 패치 경로 | 참조 | 핵심 mutant(실패 gate) | 검증 |
|---|---|---|---|---|---|
| Tenant leak(기본) | tenant-orders | orders.py, authz.py | VERIFIED | no-change·fix-only-direct-route·trust-client-tenant·tamper-test-framework(security), deny-everything(regression) 전부 NOT_VERIFIED | `PatchGradingTest` |
| Webhook replay(기본) | webhook-receiver | verify.py, dedup.py | VERIFIED | no-change·dedup-only·freshness-only·tamper-framework(security), reject-everything·dedup-by-amount(regression), compile-error(compile) | `ContentSuiteTest` |
| Over-broad token(기본) | api-gateway | tokens.py, scopes.py | VERIFIED | no-change·reports-implies-all·trust-client-scope·fix-scope-not-revoke(security), reject-everything(regression), compile-error | `ContentSuiteTest` |
| Tenant→송장·UUID(전이) | tenant-invoices | invoices.py, authz.py | VERIFIED | no-change·fix-only-direct-route·trust-client-tenant(security), deny-everything(regression), compile-error | `ContentSuiteTest` |
| Webhook→배송·순서(전이) | delivery-events | verify.py, sequence.py | VERIFIED | no-change·idempotent-only·freshness-only(security), reject-everything(regression), compile-error | `ContentSuiteTest` |
| Token→역할·팀(전이) | iam-roles | roles.py, grants.py | VERIFIED | no-change·role-implies-all·trust-client-role·fix-role-not-disable(security), reject-everything(regression), compile-error | `ContentSuiteTest` |

참조 패치는 필수 보안(deny)·정상 회귀(allow) 검사를 모두 통과해 VERIFIED, 핵심 mutant는 전부 기대한 gate에서 실패해 NOT_VERIFIED임을 실제 채점 경로로 확인했다. 탐지 drill의 seed suite(경계 20 + 무작위 100)는 `DetectionSeedSuiteTest`로 행동 규칙 solvable·이름 암기 비전이를 확인했다.

## 전이 설계(이름만 바꾼 재시도가 아님)

- Tenant→Invoices: 같은 객체 수준 권한 개념, 다른 자원(송장)·UUID 경로·다른 route. 주문 route/id 암기는 전이되지 않는다.
- Webhook→Delivery: 멱등성 개념에 더해 **이벤트 순서(monotonic seq)** 라는 다른 축을 추가. 역순 거절은 순수 중복방지에서 전이되지 않는다.
- Token→IAM: scope 컬렉션 경계가 아니라 **action 수준 역할·팀 경계**와 grant 비활성화. 컬렉션 scope 암기는 전이되지 않는다.

## 남은 품질 문제·미검증

- **출판 불가**: strong runtime verifier 없음(D-10). `accepted-verifiers` 기본값은 아직 실제 runtime이 아니다.
- **독립 검수 미완**: 외부 검수자가 플레이하지 않았다. 독립 검수 완료로 표시하지 않는다.
- **데모 결과**: 격리 미검증 local-trusted 채점. 공식 결과가 아니다.
- CTF/Wargame 발견 목표의 독립 관측 predicate는 tenant-orders만 구현(ObjectiveObserver). 전이·신규 기본 사건의 FLAG 관측은 미구현(패치 차원만 검증).
- 출판용 on-disk 번들 디렉터리(manifest/oracle/서명), 비핵심 mutant kill-ratio 90% 목표, 파일럿 10명 결과는 미완.
