# 0014 Wargame 목표(OBJECTIVE) 독립 검증

- 상태: Proposed (GAP-3 구현. 강한 runtime의 guest 밖 관측(D-10)과 다중 목표 콘텐츠 전 재검토)
- 날짜: 2026-10-08
- 담당: 프로젝트 소유자
- 관련: FR-04, [09 CTF·Wargame](../../SecDrill-docs/docs/09-ctf-wargame-guide.md), [20 채점](../../SecDrill-docs/docs/20-execution-grading.md), [ADR 0008](0008-ctf-flags-objective-observation-and-demo-results.md)

## 문제와 제약

[09](../../SecDrill-docs/docs/09-ctf-wargame-guide.md) Wargame 정의: "독립 관측기가 목표 접근을 확인하고 설명 품질은 별도 평가한다. 의도하지 않은 경로도 범위 안이고 실제 목표를 만족하면 인정하며 콘텐츠 오류를 사용자 실패로 덮지 않는다." 지금까지 OBJECTIVE 제출(계약: `challengeId`·`evidenceSeqs`·`resourceId`·`explanation`)은 기록(`HYPOTHESIS_REPORTED`)만 되고 독립 검증 verdict가 없었다(FR-04 부분, GAP-3).

## 선택

- **FLAG의 목표 관측 메커니즘을 재사용하되 flag/receipt 없이** 판정한다. runner가 Lab의 서버측 접근 기록을 oracle `verifier.requires` predicate로 관측한다([ADR 0008]). flag가 없으므로 receipt 매치 단계가 빠진다.
- **verdict**: 관측됨→PASS(gate `objective` PASS), 미관측→**FAIL**(사용자가 목표 도달을 주장했으나 서버 기록이 뒷받침하지 않음), 관측 불가(미지의 requirement·기록 읽기 실패)→platform error→재시도 후 **SYSTEM_ERROR**(콘텐츠/플랫폼 문제이지 사용자 FAIL 아님). FLAG는 두 신호(match+관측)라 관측 공백을 INCONCLUSIVE로 두지만, OBJECTIVE는 관측이 유일 신호라 미관측을 FAIL로 둬 검증에 실효를 준다.
- **설명은 별도·기록**: `evidenceSeqs`·`resourceId`·`explanation`은 `HYPOTHESIS_REPORTED`(USER_REPORTED) 증거로 남기고 자동 채점하지 않는다. verdict와 설명 품질을 섞지 않는다.
- **채점 job 생성 조건(**dangling 방지**)**: mode가 WARGAME·PURPLE이고 고정 manifest에 해당 `challengeId`의 `kind=OBJECTIVE` 챌린지가 있으며 **라이브 Lab이 있을 때만** GRADE job을 만든다. 아니면 기록만 한다(채점자 없는 job을 남기지 않음 — DEFECT-1 재발 방지).
- **lab-scoped**: 관측 대상은 Session 자신의 Lab뿐이라 cross-session 위험이 없다. OBJECTIVE는 generic worker claim에서 제외하고 FLAG처럼 **Lab 호스팅 runner**가 claim한다.

## 비교한 대안

- OBJECTIVE를 영구히 기록만: FR-04 Wargame 독립 검증 미충족.
- 설명 텍스트를 자동 채점: 09가 "설명 품질은 별도 평가"로 분리. LLM 설명 평가는 범위 밖.
- 미관측을 INCONCLUSIVE로: 검증이 결코 FAIL하지 않아 무의미. 관측이 유일 신호라 FAIL로 둔다.

## 비용과 위험

- local-trusted에서 기록은 guest 안에 있어 코드 실행이 가능한 학습자가 위조할 수 있다([ADR 0008]의 한계 그대로). 강한 runtime이 관측을 guest 밖으로 옮겨야 한다(D-10). 결과는 demo.
- 현재 oracle는 시나리오당 단일 `verifier.requires`를 쓴다. 다중 독립 목표 콘텐츠는 per-challenge verifier 매핑이 필요하며 그때 재검토한다.

## 검증 증거

- `CtfFlowTest`(또는 전용 테스트): WARGAME OBJECTIVE 제출이 관측되면 PASS, 관측되지 않으면 FAIL, 가설은 `HYPOTHESIS_REPORTED`로 기록. generic worker는 OBJECTIVE를 claim하지 않는다.
