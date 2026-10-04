# 12. T12 리포트·Replay·스킬·추천 구현

SecDrill T12를 구현하라.

10-evaluation-evidence, 22-replay,
23-adaptive-randomization과 관련 계약을 읽어라.

다음을 연결하라.

- 평가 차원과 evidence anchor가 있는 리포트
- 도움 수준과 판정 범위 표시
- 관측 기록과 모델 재계산의 구분
- checkpoint seek와 timeline 탐색
- 누락·만료 Artifact 표시
- taxonomy·policy 버전이 있는 skill projection
- UNKNOWN과 confidence 분리
- 이유가 설명되는 상위3개 추천
- parent·해설·힌트 노출의 전파

처음부터 재생과 checkpoint seek의 상태 digest가 같아야 한다.
플랫폼 오류·반복 풀이·상관된 증거로 역량을 과대 평가하지 않는다.
재채점은 이전 결과를 지우지 않고 새 revision으로 반영한다.

사용자가 근거를 따라가 점수와 다음 추천을 이해할 수 있는지
E2E와 fixture로 검증하라.
AI 설명은 이 단계의 필수 기능으로 추가하지 않는다.
