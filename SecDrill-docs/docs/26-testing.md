# SecDrill 테스트 전략

테스트는 기능 결과와 신뢰 경계를 함께 검증한다. mock 성공만으로 격리·멱등성·채점 신뢰성을 증명하지 않는다. 실제 런타임이 필요한 검사는 해당 환경이 없으면 skipped로 명시하고 출시 게이트를 통과한 것으로 처리하지 않는다.

| 계층 | 대상 | 핵심 사례 |
|---|---|---|
| 단위 | reducer·rubric·skill·DSL | 동일 seed, N/A 분모, UNKNOWN, hint 배수, 필수 gate |
| 도메인 | 상태·CAS·owner | terminal 전이 차단·취소 경합·artifact scope |
| DB 통합 | 제약·Outbox·원장 | 중복 제출·active revision·head lock·append-only |
| 계약 | OpenAPI·event schema | 요청/응답 validator·unknown enum·schema compatibility |
| 실행 통합 | strong runner·grader | compile 격리·timeout·stdout flood·tamper mutant |
| 콘텐츠 | 기본·전이·seed | reference 통과·mutant 실패·hidden oracle 비노출 |
| E2E | 사용자 플로우 | CTF→Purple→report→Transfer·재접속·삭제 |
| 보안 | 18 위협 | cross-owner·proxy SSRF·egress·CSRF·출력 XSS |
| 장애 | queue·worker·store | late result·duplicate·partial side effect·reconciliation |

## 핵심 assertion

한 요청 재전송은 한 Submission만 만든다. 동일 eventId는 consumer마다 한 업무 변경만 만든다. lease가 바뀐 뒤 이전 결과는 활성 평가를 만들지 않는다. 취소 뒤 도착한 LabReady는 자원 회수로 이어진다. hidden test 수정 mutant가 통과하지 않는다. 무조건 거절 패치는 보안 gate가 통과해도 정상 회귀에서 실패한다. 다른 Session 플래그는 실패한다. 원장 gap은 report의 평가 범위에 표시된다.

## 품질 기준

커버리지 비율은 보조 지표다. domain state·IAM·grade gate·idempotency의 분기와 핵심 불변식은 테스트로 추적한다. 콘텐츠 mutation coverage는 08의 핵심 100%·비핵심 90% 목표를 따른다. flaky test는 재시도 성공으로 숨기지 않고 원인·빈도·차단 여부를 기록한다.

## fixture와 데이터

로그·합성 계정·seed·image digest를 고정하고 mock clock을 사용한다. 실제 테스트는 runtime 종류·host kernel·자원 설정을 결과에 기록한다. raw secret·실제 고객 데이터는 fixture에 포함하지 않는다. reference answer와 hidden suite는 learner-facing 테스트 bundle에 넣지 않는다.

## 출시 필수 체크

acceptance matrix FR-01~10, NFR-01~05를 모두 PASS로 연결하고 DB restore·삭제 리허설·strong runtime 격리·6 콘텐츠·파일럿 학습 확인을 첨부한다. 이 문서 세트 자체는 개발 설계 산출물이며 실제 제품 테스트가 완료된 증거를 대신하지 않는다.
