# 10. T10 탐지 DSL과 사고 대응 모델 구현

SecDrill T10을 구현하라.

21-detection-ir, 10-evaluation-evidence,
22-replay, 23-adaptive-randomization과 계약을 읽어라.

탐지:
제한 JSON AST, 필드 비교, window 집계,
깊이·노드·시간 제한, training/holdout 분리,
attack episode 기준 TP·FP·FN,
precision·recall·F1·FPR·latency를 구현하라.

분모0은 N/A로 처리하고 ground truth label은 규칙에 제공하지 않는다.
단순 IP·이름 암기로 통과하는지 변형 holdout으로 검사하라.

대응:
버전·seed가 고정된 순수 reducer와
문서의 네 액션을 구현하라.
피해·가용성·정상 업무·증거 보존의 변화를 기록하라.

SIMULATED, OBSERVED, USER_REPORTED를 구분한다.
모델 액션만 수행한 것을 실제 VM 조치로 표시하지 않는다.

동일 입력 재현, 액션 충돌, 정상 업무 손상,
미탐·미복구·로그 누락을 테스트하라.
