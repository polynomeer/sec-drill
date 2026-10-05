# SecDrill Detection과 Incident Response 시뮬레이션 설계

탐지는 공격과 정상 행동을 구분하는 능력을, 대응은 보안 효과·업무 손상·증거 보존의 판단을 평가한다. 실제 Lab 이벤트와 모델 계산을 UI·리포트에서 명확히 구분한다.

## 탐지 DSL과 데이터

MVP 규칙은 JSON AST로 제한한다. 필드는 eventType, actorId, tenantId, resourceTenantId, status, routeGroup, count, windowSeconds이며 연산은 eq, neq, and, or, count_gte다. eq/neq는 literal value 또는 compareField 중 하나와 비교한다. 예를 들어 tenantId neq compareField resourceTenantId는 정규화된 서버 로그 필드를 비교한다. 누락 필드 비교는 false, count 집계는 actorId+routeGroup과 window 기준이며 같은 eventId는 한 번만 센다. 임의 SQL·정규식 무제한·외부 함수·shell을 허용하지 않는다. 깊이 8, 노드 64, window 300초, 평가 timeout 5초를 제한한다. 구현(T10): count_gte 안의 count_gte는 거절한다(평가를 이벤트 수에 선형으로 유지). 평가 예산 초과는 사용자 자원 한도(FAIL, gate `resource_limit`)다.

탐지 입력은 eventTime 기반 합성 로그이며 rule에 정답 attackLabel을 주지 않는다. visible training과 hidden holdout을 분리하고 actor·IP·route 이름을 변형한다. ground truth는 caseId·attackEpisodeId·유효 탐지 window로 정의한다. 단순 이벤트 여러 개로 같은 공격을 여러 TP로 세지 않는다.

TP는 공격 episode window 안의 첫 유효 alert, FN은 탐지되지 않은 episode, FP는 공격 window 밖 정상 actor/window의 alert다. precision=TP/(TP+FP), recall=TP/(TP+FN), F1은 조화평균이다. TN은 사전 정의한 정상 actor/window 집합의 무경보 수로 계산하고 FPR=FP/(FP+TN)을 제공한다. 분모 0은 N/A이며 0% 또는 100%로 꾸미지 않는다. 구현: 정상 window 밖이면서 공격 window 밖인 alert는 하나씩 FP로 센다(라벨 없는 시간에 alert를 흘려 넣어 precision을 숨기지 못하게). 비율은 basis point로 계산하고 N/A 지표는 평가 dimension에서 뺀다. 판정은 숨은 holdout으로만 하고 training 지표는 참고로 보여준다. training·holdout seed는 서버 전용 Session seed에서 용도별로 파생한다. latency는 첫 악성 이벤트에서 첫 alert까지이며 미탐은 별도 FN으로 남긴다.

임계값은 콘텐츠 rubric에 둔다. 예시 tenant leak은 recall>=0.9, precision>=0.8, p95 latency<=30 simulated seconds를 목표로 하고 정상 데이터 비율과 episode 수를 함께 공개한다. 이 수치는 설계 가정이며 운영 SOC 성능 기준으로 주장하지 않는다.

## IR 모델

SystemState는 compromisedIdentities, activeTokens, accessibleAssets, leakedSyntheticRecords, availability, evidenceCoverage, workloadSuccess, tick을 가진다. reducer는 `next(state, action, injectedEvent, versions, seed)`로 계산한다. 공격자는 제공된 deterministic event schedule을 따르며 사용자의 arbitrary guest 코드와 모델 내부 공격 일정을 동일한 사실로 간주하지 않는다.

| MVP 액션 | 보안 효과 | 부작용·조건 |
|---|---|---|
| REVOKE_TOKEN | 해당 토큰의 새 요청 차단 | 동일 토큰의 정상 자동화 실패 |
| DISABLE_ENDPOINT | 대상 route group 접근 차단 | 정상 workload 성공률 감소 |
| ISOLATE_WORKLOAD | 모델의 공격 경로 차단 | 서비스 가용성 손실·로그 일부 중단 |
| ENABLE_AUDIT | 이후 관측 coverage 증가 | 추가 비용; 과거 이벤트 생성하지 않음 |

실제 API에서 안전하게 수행 가능한 token revoke는 실제 Lab 조작과 모델 액션을 각각 기록하고 성공·실패를 분리한다. isolate 모델만 적용했는데 실제 VM을 격리했다고 표시하지 않는다. 구현(T10, engine `ir-v1`): 모든 액션 응답은 `representation: SIMULATED`, Evidence는 `SIMULATOR`/`SIMULATED`이며 실제 Lab 조작 API는 아직 없다. 공격자는 `tok-sync`(정상 자동화와 공유)로 `orders`를 읽고 그 route가 막히면 `orders-legacy`로 우회한다. 효과가 없는 액션(이미 적용, 대상이 없어진 audit)은 409로 거절하고 tick을 쓰지 않는다. 상태는 저장하지 않고 (seed, engine, 수락된 액션)에서 재계산하며 액션마다 state digest를 남긴다.

## 대응 평가

피해는 leakedRecords 합성 수와 exposure duration, 업무 손상은 baseline 대비 workload 실패·unavailable tick, 증거 보존은 필요한 로그/타임라인 존재율로 측정한다. MTTD는 첫 침해부터 사용자 탐지 인정 tick, MTTC는 containment 효과 확인 tick, MTTR은 필수 정상 workload 회복 tick으로 정의한다. 미복구는 censored로 표시하고 임의 0초를 넣지 않는다. 구현: 시간 단위는 tick이고 MTTC·MTTR이 미도달이면 null(censored)이다. 증거 누락은 workload 격리로 로그가 끊긴 tick 수로 센다. 모든 endpoint를 막은 대응은 공격 중단에는 성공해도 availability·회귀 gate에서 손실이 드러난다.
