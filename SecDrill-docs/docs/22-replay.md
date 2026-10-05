# SecDrill Replay 설계

Replay는 당시 기록된 사실과 판단을 검토하는 기능이다. VM 메모리·네트워크를 과거로 완전히 되돌리는 기능을 약속하지 않는다. 재실행은 별도의 새로운 환경에서 수행하며 실제 결과가 달라질 수 있다.

## 두 재생 경로

규칙 기반 IR은 초기 상태·seed·engineVersion·ordered events로 순수 reducer를 재실행한다. 실제 Lab는 요청 요약·관측 로그·액션·상태 snapshot을 재생한다. UI는 각 항목을 `SIMULATED`, `OBSERVED`, `USER_REPORTED`로 표시하며 관측되지 않은 상태를 추정해 사실로 보이지 않는다.

## 저장 계약

Replay manifest는 sessionId, contentDigest, engineVersion, randomizationVersion, firstSeq, lastSeq, chunks, checkpoints, gaps를 가진다. chunk는 seq 범위·artifactRef·digest·schemaVersion이다. IR checkpoint는 100 events 또는 30 simulated seconds마다 stateDigest와 reducer state를 저장한다. 실제 snapshot은 해당 시점 관측의 복제이며 원장 Evidence ID를 연결한다.

seek는 가장 가까운 이전 checkpoint + 이후 이벤트를 적용한다. 서버 reducer가 canonical 상태를 만들고 브라우저는 결과를 렌더링한다. 브라우저 자체 reducer를 추가하면 동일 fixture와 stateDigest 검사로 두 구현의 divergence를 막아야 한다. 구 engine을 지원하지 못하면 stored snapshot read-only 재생으로 degrade한다.

## 접근과 누락

공개 replay에는 hidden oracle·정답·정상/공격 label을 세션 정책에 따라 제거한다. 같은 Session의 과거 리포트도 현재 owner 검사를 거친다. 원본 raw log가 TTL로 사라지면 metadata·digest·요약만 표시하고 재생 가능 범위를 명시한다. gap은 seq 또는 collector receipt에서 탐지하고 해당 구간 상태를 unknown으로 표시한다.

## 학습 기능

사용자는 첫 의심 신호·탐지 규칙 배포·대응·패치·회귀를 anchor로 이동한다. 처음 관측 가능한 신호와 실제 판단 사이의 지연을 함께 본다. postmortem의 evidenceRefs는 해당 seq로 연결된다. AI 요약을 추가할 때는 해당 anchor만 인용하고 없는 로그를 만들어내지 않는다.

## 후속 counterfactual

모델 위에서 다른 액션을 적용하는 분기 replay는 P1이다. parentReplay·branchAction·engineVersion을 기록하고 원본 증거를 변경하지 않는다. 모델 예측이며 실제 사고 결과의 증명이 아님을 표시한다. 실제 코드 재실행은 새로운 job과 Lab generation을 사용하고 원래 평가에 자동 덮어쓰지 않는다.

## 수용 기준

동일 fixture의 처음부터 재생과 checkpoint seek 상태 digest가 같아야 한다. 구현(T10): IR 상태는 (seed, engine, 수락된 액션)에서 재계산하고 `applied_actions.state_digest`와 비교한다. 이력이 바뀌면 검증이 실패한다. checkpoint·seek·manifest는 T12다. seq 역순·중복·누락·삭제된 artifact·구버전 reducer·권한 없는 anchor를 테스트한다. 실제 네트워크 지연은 Replay에서 원래 observedAt를 보존하고 정렬은 seq 기준으로 일관되게 유지한다.
