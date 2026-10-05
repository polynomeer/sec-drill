# SecDrill 평가와 스킬 프로파일과 Evidence Ledger 설계

평가는 관측된 사실, 해석, 추천을 분리한다. 플래그 성공과 무힌트 사고 해결은 같은 증거가 아니다. 원장은 행동과 판정의 근거를 보관하고 숙련도는 버전 있는 projection으로 계산한다.

## 평가 차원

| 차원 | Purple 가중치 | 근거 |
|---|---|---|
| 취약 경로 재현·영향 | 15 | 독립 objective verifier 이벤트 |
| 관측·조사 | 10 | 정확한 이벤트 연결과 timeline 답안 |
| 탐지 | 15 | 숨은 공격·정상 holdout precision/recall/latency |
| 대응 | 15 | 피해 축소·정상 업무·증거 보존 model 결과 |
| 근본 수정 | 25 | hidden bypass tests와 경계별 검증 |
| 정상 회귀 | 10 | 필수 정상 워크로드 통과율 |
| 회고 | 10 | 원인·범위·대응·재발방지와 실제 evidenceRefs |

0~100 점수는 이 사건 안의 피드백이다. 핵심 보안 gate 또는 필수 회귀가 하나라도 실패하면 패치는 NOT_VERIFIED이며 총점이 높아도 VERIFIED가 아니다. 로그 손실이나 환경 실패는 INCONCLUSIVE다. CTF는 objective 결과와 개인 성취 점수를, Wargame은 재현·영향·원인 설명을 별도 rubric으로 평가한다.

AI 설명은 선택적 부가 기능이다. 규칙·테스트로 결정된 공식 점수는 AI가 바꾸지 않는다. 회고 rubric의 MVP는 구조화 필드와 참조 유효성·사람 검수로 평가하며 의미 품질 평가는 experimental로 표시한다. 공급자 장애 시 허구의 고정 점수를 만들지 않는다.

## 원장 구조와 신뢰

Evidence에는 id, sessionId, seq, type, source, trustLevel, occurredAt, ingestedAt, artifactRef, payloadDigest, previousHash, hash, schemaVersion이 있다. seq와 해시는 트랜잭션 내 Session 원장 head lock으로 부여한다. 서버가 실제로 수집한 `OBJECTIVE_CONFIRMED`, `TEST_RESULT`, `ACTION_APPLIED`, `HINT_GRANTED`, `POSTMORTEM_SUBMITTED`와 사용자 주장 `HYPOTHESIS_REPORTED`를 구분한다. 사용자가 클릭했다고 소스를 이해했다는 증거를 만들지 않는다. CTF의 `OBJECTIVE_CONFIRMED`는 Lab을 호스팅한 runner의 collector가 target의 서버 측 접근 기록에서 의도된 접근을 관측했을 때만 `COLLECTOR`/`OBSERVED`로 기록하고, 플래그 일치만으로는 기록하지 않는다. 격리가 검증되지 않은 runtime(local-trusted)이나 fake worker의 평가는 `demo`로 표시하며 공식 결과·skill projection의 근거로 쓰지 않는다.

hash는 canonical JSON과 직전 hash의 SHA-256으로 계산한다. DB UPDATE/DELETE 차단·별도 서명 checkpoint·외부 저장으로 변조 탐지를 강화하지만 DB 최고 권한의 악의까지 불가능하게 만든다고 주장하지 않는다. 원장 row에는 비밀·raw source를 저장하지 않고 별도 보관·삭제 가능한 Artifact 참조만 둔다.

## 스킬 projection 정책 v1

역량 축은 AUTHORIZATION, INPUT_BOUNDARY, TOKEN_SECURITY, ATTACK_REASONING, OBSERVATION, DETECTION, RESPONSE, SECURE_PATCHING, FORENSICS다. taxonomyVersion을 기록하며 역량 키 변경은 migration map을 제공한다.

각 사건 계열·세부 역량에서 하루 한 개의 가장 강한 판정만 표본으로 채택한다. base weight는 CTF objective 0.5, Wargame 독립 증명 1.0, Purple gate 통과 1.5, 무힌트 Transfer 2.0이다. H1~H2는 0.7, H3~H4·해설은 0.3 도움 배수를 적용한다. 시스템 오류·사용자 단순 로그 조회·오답 플래그는 표본에서 제외한다. Transfer와 원본 사건의 상관된 증거를 독립 표본으로 중복 세지 않는다.

관측 성공률은 `sum(weight × outcome)/sum(weight)`이며 outcome은 해당 역량 gate의 0 또는 1이다. 연속 점수를 심리측정상 숙련 확률로 주장하지 않는다. level은 표본 3개·서로 다른 계열 2개 미만이면 UNKNOWN, 이후 성공률 <0.5 DEVELOPING, <0.8 PRACTICING, >=0.8 DEMONSTRATED다. DEMONSTRATED에는 서로 다른 계열의 무힌트 Transfer 2개가 추가로 필요하다. confidence는 LOW(표본 <5 또는 계열 <3), MEDIUM(5~9 및 계열 >=3), HIGH(>=10 및 계열 >=4)로 별도 표시한다. 이는 제품용 초기 휴리스틱이며 파일럿 calibration 대상이다.

## 정정과 설명 가능성

재채점은 새 EvaluationRevision을 추가하고 동일 policyVersion의 최신 활성 revision만 projection에 반영한다. 이전 리포트에는 당시 revision을 고정하고 새 결과로 변경된 이유를 표시한다. 사용자에게 점수·근거·도움·평가 범위·정책 버전을 제공하고 이의를 기록해 운영자 검수로 연결한다.
