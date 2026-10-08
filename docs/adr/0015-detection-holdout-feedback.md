# 0015 탐지 holdout 피드백 축소(SEC-1)

- 상태: Proposed (SEC-1 적용. 제출 quota·calibration 도입 전 재검토)
- 날짜: 2026-10-08
- 담당: 프로젝트 소유자
- 관련: [T15](../development/T15.md) SEC-1, FR-08, [20 채점](../../SecDrill-docs/docs/20-execution-grading.md), [21 탐지](../../SecDrill-docs/docs/21-detection-ir.md)

## 문제와 제약

[T15](../development/T15.md) SEC-1: 탐지 채점이 매 제출마다 숨은 holdout의 **정확 혼동행렬 수치**(truePositives/falsePositives/falseNegatives/trueNegatives, p95LatencySeconds)를 학습자 가시 `TEST_RESULT` 증거로 남겼다. 재제출을 반복하며 규칙을 조금씩 바꾸고 이 정수들의 변화를 관찰하면 좌표하강식으로 holdout의 라벨을 부분 역설계해, 일반화 없이 gate를 통과(gaming)할 수 있다(18 평가 조작, 20 anti-tamper). 반면 `ResponseDrillTest`는 holdout의 **집계 비율**(`holdout.recall` 등)을 과적합 격차 교육용으로 학습자에게 보여주도록 명시 검증한다 — 이 교육 신호는 유지해야 한다.

## 선택

- 학습자 가시 `TEST_RESULT` 증거에서 **정확 혼동행렬 수치와 p95를 제거**하고, holdout 스코어링이 돌았다는 사실(submissionId·dataset·generator)만 남긴다.
- 교육용 집계 비율 dimension(`training.*`·`holdout.*` recall/precision/f1/falsePositiveRate, basis point)과 gate 판정(detection PASS/FAIL)은 그대로 둔다. 비율은 bps로 반올림돼 거칠고, 격차 교육에 필요하다.
- 결과: 날카로운 gaming 벡터(정수 count)는 사라지고, 과적합 격차 교육 신호는 보존된다. 평가 verdict·gate·점수 공식은 바뀌지 않는다.

## 비교한 대안

- holdout 비율 dimension까지 제거: `ResponseDrillTest`가 검증하는 교육 동작을 되돌리게 되고, 학습자가 일반화 격차를 볼 수 없다.
- 제출 quota/쿨다운으로 재제출 제한: gaming을 더 넓게 막지만 별도 기능(요청 시 추가). 이번에는 피드백 축소만 적용한다.
- 그대로 두기: SEC-1 잔존.

## 비용과 위험

- holdout 집계 비율은 여전히 거친 gradient다. 정밀 역설계는 어려워졌으나 완전 차단은 아니며, 필요 시 제출 quota를 추가한다.
- 증거의 holdout count를 더는 남기지 않으므로, 운영자가 사후에 holdout 성능 상세를 보려면 재채점 로그·grader 측 기록을 봐야 한다(학습자 가시 경로에는 없음 — 의도).

## 검증 증거

- `ResponseDrillTest`(탐지): holdout 비율 dimension과 gate 판정은 그대로 통과. `TEST_RESULT` 증거 행은 여전히 SIMULATOR/SIMULATED로 남고 payload에 정수 count가 없다.
- `DetectionTest`(scorer 단위): 지표 계산은 바뀌지 않았다(제거한 것은 증거 노출이지 계산이 아님).
