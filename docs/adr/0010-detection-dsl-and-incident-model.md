# 0010 탐지 DSL 평가와 사고 대응 모델

- 상태: Proposed (T10 구현. Purple 통합 리포트(T12)와 실제 Lab 조작 액션 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-011(real Lab와 deterministic IR 결합) 일부
- 관련: T10, FR-05, [10](../../SecDrill-docs/docs/10-evaluation-evidence.md), [21](../../SecDrill-docs/docs/21-detection-ir.md), [22](../../SecDrill-docs/docs/22-replay.md), [23](../../SecDrill-docs/docs/23-adaptive-randomization.md)

## 문제와 제약

탐지 규칙은 학습자가 쓰지만 임의 코드가 아니어야 하고, 정답 label을 볼 수 없어야 하며, 이름 암기로 통과하면 안 된다. 대응은 효과와 부작용을 같은 모델 안에서 보여줘야 하고, 모델 계산을 실제 Lab 조치로 보이면 안 된다. 같은 입력은 같은 결과를 내야 재생(22)이 가능하다.

## 선택

- **엔진 모듈** `:execution:simulation`: Spring·DB·I/O가 없는 순수 Kotlin. 모든 값은 seed와 버전으로 결정된다.
- **탐지 DSL**: OpenAPI `DetectionNode`를 그대로 파싱한다. 깊이 8·노드 64·window 300초·평가 예산 5초, count_gte 중첩 금지. 누락 필드 비교는 false, count는 actorId+routeGroup 단위의 서로 다른 eventId. 이벤트 타입에는 label 필드가 없다(구조적으로 규칙이 볼 수 없음).
- **데이터**: `tenant-orders-logs/1` 합성 로그. 정상 사용자, 다른 tenant를 가끔 조회하는 지원 담당자(단일 cross-tenant 조회는 공격이 아님), 한 사용자의 cross-tenant 조회 burst인 공격 episode. training과 holdout은 Session seed에서 파생한 서로 다른 seed와 다른 route 이름을 쓴다. holdout은 API로 제공하지 않는다.
- **지표**: episode당 TP 1, 정상 window 단위 FP·TN, 어느 window에도 속하지 않는 alert는 각각 FP. 비율은 basis point, 분모 0은 N/A(dimension에서 제외). gate는 holdout의 recall·precision·p95 지연(oracle `detection` 임계값). episode가 없으면 INCONCLUSIVE(SYSTEM_ERROR), 예산 초과는 FAIL(`resource_limit`).
- **채점 위치**: Control Plane 안의 `DetectionGradingService`. 실행하는 것이 학습자 코드가 아니라 제한된 AST이므로 격리 runtime이 필요 없다. 일반 worker는 DETECTION job을 받지 않는다. Evidence는 합성 데이터 계산이므로 `SIMULATOR`/`SIMULATED`, 학습자 설명은 `USER`/`USER_REPORTED`.
- **IR 모델** `ir-v1`: workload 2개, route 3개, 정상 자동화와 공유된 도난 토큰, 주 route가 막히면 legacy route로 우회하는 공격 일정. 네 액션의 효과·부작용은 21의 표대로이며 tick마다 유출 수, 가용성, 정상 업무 성공률, 증거 coverage, 증거 누락 tick을 갱신한다. MTTC·MTTR은 tick 단위, 미도달은 null(censored).
- **액션 API**: PURPLE Session, manifest `actions`에 있는 종류만. 상태는 저장하지 않고 (seed, engine, 수락된 액션)에서 재계산하며 액션마다 `applied_actions.state_digest`를 남긴다. 효과 없는 액션은 409로 거절하고 기록하지 않는다. 응답은 항상 `SIMULATED`이고 실제 Lab 조작은 하지 않는다. engine이 다른 Session은 새 액션을 받지 않는다(읽기 전용).

## 비교한 대안

- 규칙을 SQL·정규식으로 받기: 21이 금지하고 비용 상한을 보장할 수 없다.
- 상태를 매 액션 저장: 재현 가능성을 증명하지 못한다. 재계산 + digest 비교가 22의 seek parity와 같은 원리다.
- 효과 없는 액션도 tick을 소비하며 기록: 학습자가 같은 액션을 반복해 시간을 끌 수 있고, 모델에 없는 사실이 원장에 남는다.
- 탐지를 runner에서 실행: 비신뢰 코드가 아니어서 격리 이득이 없고 지연만 늘어난다.

## 비용과 위험

- 합성 로그 생성기와 IR 모델은 한 사건에 맞춘 단순 모델이다. 운영 SOC 성능 주장 근거가 아니다(21).
- holdout 변형은 이름·시간·route 이름까지다. 행동 자체를 바꾸는 변형(23의 Transfer)은 아니다.
- 증거 coverage의 "추가 비용"은 아직 모델에 없다.
- 실제 Lab에서의 token revoke와 모델 액션의 이중 기록은 미구현이다(21). 지금은 모델 액션만 있다.
- `verifyReplay`는 서비스 함수이며 Replay API·checkpoint·manifest는 T12다.

## 검증 증거

- `DetectionTest` 8건: 행동 기반 규칙이 training·holdout 모두 PASS, 이름·route 암기 규칙은 training PASS·holdout FAIL, 단일 이벤트 규칙은 지원 업무 FP로 FAIL, 분모 0 N/A·미탐 FN·지연 N/A, label 비노출, 깊이·노드·window·중첩·연산자·예산 제한, 누락 필드 false·중복 eventId 1회, 데이터셋 결정성·holdout 이름 변형
- `IncidentModelTest` 8건: 동일 seed·액션 재현과 seed 의존, 무대응 미격리·미복구(censored), 토큰 회수의 자동화 손상, 주 route만 차단 시 우회, workload 격리의 가용성·로그 손실, audit의 사후 coverage만 증가, 반복·불가 액션 무효와 미지 대상 거절, 전체 차단의 업무 손상
- `ResponseDrillTest` 3건: 액션 SIMULATED 응답·replay·409/422·version CAS·미기록, Evidence SIMULATED, replay digest 검증과 이력 변조 탐지, CTF 422·타인 404; 데이터셋 label 없음, 행동 규칙 PASS, 암기 규칙 holdout FAIL, 무경보 규칙의 N/A 제외, 깊이·label 필드 422, 일반 worker 미배정; SIMULATED·USER_REPORTED 구분과 OBSERVED 없음
- 미검증: 실제 Lab 액션, Replay API, 다른 사건의 생성기, 대규모 로그에서의 평가 시간

## 결과와 되돌리는 조건

실제 Lab token revoke를 추가하면 모델 액션과 별도로 OBSERVED 결과를 기록한다. 생성기·모델을 바꾸면 버전을 올리고 기존 Session은 이전 버전으로 재생하거나 읽기 전용으로 둔다.

## 영향을 받는 문서·계약·테스트

`V8__applied_actions.sql`, `contracts/schema.sql`·`enums.json`(IrActionType)·`openapi.yaml`(getDetectionDataset·DetectionDataset·DetectionEvent)·`fixtures/api.json`, 팩 10·14·15·21·22, `:execution:simulation`, `:control-plane:response`, `:control-plane:submission`(DETECTION 수락·`DetectionGradingService`), [T10](../development/T10.md)
