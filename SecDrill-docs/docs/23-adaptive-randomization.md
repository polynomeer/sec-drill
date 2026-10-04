# SecDrill 랜덤화와 Adaptive Drill 설계

랜덤화는 풀이 복제를 줄이되 학습 목표와 난이도를 보존해야 한다. 추천은 부족한 증거를 채우는 선택을 돕고 학습자에게 이유를 설명한다.

## seed와 동치 변형

Session seed는 서버의 CSPRNG로 생성하고 engineVersion·randomizationVersion과 함께 고정한다. seed는 learner API에 노출하지 않는다. 재생·검수용 운영 권한으로만 접근한다. 같은 seed가 flag 비밀을 결정하지 않으며 flag nonce는 별도 secret이다.

MVP 변형은 합성 사용자·리소스 ID·이름·정상 로그 순서·공격 시간 범위로 제한한다. 권한 경계·우회 경로·정상 업무 invariant는 유지한다. REST→GraphQL·role model 변화처럼 풀이 개념과 난이도가 변하는 것은 별도 Transfer ScenarioVersion이지 단순 seed다.

저작 검증은 경계 seed 20개와 무작위 seed 100개에서 정상 baseline·참조 패치·핵심 mutant를 실행한다. generator가 solvable하지 않은 seed를 만들면 publish를 막는다. 난이도 영향은 파일럿의 시간·도움·독립 성공 분포로 확인하고 원래 seed를 몰래 바꾸지 않는다.

## 추천 정책 v1

추천 후보는 공개·지원 모드·선수 역량·미노출 사건 계열·실행 예산 조건을 만족해야 한다. 점수는 `0.4 × 역량 evidence gap + 0.3 × 낮은 최근 독립 성공 + 0.2 × 사건 계열 novelty + 0.1 × 선호 적합`으로 초기 가정한다. 단위는 모두 0~1로 정규화하며 UNKNOWN은 낮은 성공률로 간주하지 않고 evidence gap으로만 반영한다.

추천 상위 3개를 이유·예상 시간·필요 Lab 자원과 함께 제공한다. confidence LOW면 진단 과제, 독립 gate 실패가 반복되면 개념 drill, 해설 성공이면 다른 계열 Transfer를 추천한다. 사용자는 추천을 무시하거나 원하는 모드를 선택할 수 있다.

## 추천 provenance

recommendation에는 policyVersion, sourceEvidenceWatermark, candidates, scores, chosenReason, exposureHistory를 기록한다. 새로운 결과가 없어도 단순 UI 조회로 mastery를 갱신하지 않는다. 동일 계열 반복 풀이를 confidence 증가로 과대 평가하지 않는다. 삭제된 증거는 projection 재계산과 추천 cache invalidation에 반영한다.

## 후속 adaptive 확장

온라인 bandit·IRT·LLM 콘텐츠 생성은 P2 검토다. 표본이 적을 때 false precision을 만들지 않고 규칙 기반의 설명 가능한 추천으로 시작한다. LLM이 새 문제를 생성해도 자동 출판하지 않고 08의 reference·mutant·seed·2인 검수 게이트를 거친다. 추천 품질은 무힌트 Transfer·사용자 선택·중도 이탈을 함께 평가한다.
