# SecDrill 실행과 채점 엔진 설계

공식 채점은 사용자 주장이나 stdout에 의존하지 않고 신뢰 경계 밖 supervisor가 수집한 관측과 숨은 테스트로 판정한다. compile도 비신뢰 실행이므로 격리한다.

## 파이프라인

1. API는 허용 경로·크기·타입·모드·state를 확인하고 canonical 제출 bundle digest를 저장한다.
2. submission·job·outbox·evidence를 한 transaction에 커밋하고 202를 반환한다.
3. Orchestrator가 quota와 pool을 확인해 job을 dispatch한다.
4. Agent가 claim해 fencing token과 lease를 받고 이미지·bundle signature를 검증한다.
5. 새 grading VM에 기준 repo·사용자 허용 파일·dependency cache를 배치한다. learner VM filesystem을 공유하지 않는다.
6. compile → 정상 baseline → 보안 재현 → 변형 공격 → 정상 회귀를 실행한다. 테스트 driver와 oracle는 사용자 수정 경로 밖에 둔다.
7. supervisor가 timeout·exit·관측 결과를 정형 result로 만들고 digest와 함께 ingest에 보낸다.
8. Control Plane이 현재 token·attempt·job 상태를 확인하고 EvaluationRevision·Ledger·projection event를 저장한다.

## 현재 구현(T08, local-trusted)

grading-strong microVM이 없어(D-10) runner는 local-trusted Docker에서 job attempt마다 새 환경을 만든다: `--internal` network, learner 허용 파일만 담은 volume(network 없는 helper가 기록), network 없는 compile 컨테이너(seccomp 확인 후 `compileall`, exit 3만 사용자 compile 오류), 패치된 app 컨테이너, 별도 supervisor 컨테이너. supervisor가 비공개 `hidden-tests.json`의 요청을 보내 상태 코드와 주문 id로만 판정하고 test id별 성공 여부만 보고한다. app은 test plan을 받지 않고 supervisor 출력에 쓸 수 없다. Control이 oracle `hiddenTests`의 expected(deny=보안, allow=회귀)로 gate를 계산한다. 학습자에게는 compile·security·regression gate만 보이고 test id·요청·기대값은 보이지 않는다. 결과는 모두 demo다.

## adapter와 판정

RuntimeAdapter는 imageDigest, compileCommandTemplate, allowedFiles, resourceProfile, resultParser를 제공한다. shell command에 사용자 입력을 이어 붙이지 않고 argv 배열을 사용한다. MVP Python 한 개부터 구현하고 다른 언어는 해당 adapter의 격리·compile·test contract를 통과한 뒤 추가한다.

| 상황 | 공식 결과 | 재시도 |
|---|---|---|
| 정상 실행, 필수 gate 전부 통과 | PASS / VERIFIED | 없음 |
| 코드 오류·필수 보안·회귀 실패 | FAIL / NOT_VERIFIED | 없음; 새 제출 필요 |
| 사용자가 자원 상한 초과 | FAIL, RESOURCE_LIMIT | 없음; 제한 명시 |
| host 장애·스토어 오류·collector 손실 | SYSTEM_ERROR / INCONCLUSIVE | 최대 총 3 attempt |
| 서명 불일치·invalid content | SYSTEM_ERROR, content quarantine | 자동 재시도 없음 |
| 취소·lease stale | 공식 결과 반영 없음 | 현재 desired state만 따름 |

## anti-tamper

user code가 test framework·result file·DB fixture를 바꾸는 mutant를 포함한다. 채점 결과 파일을 같은 VM 안에서 사용자가 쓸 수 있게 두면 안 된다. 외부 driver는 API responses와 별도 telemetry를 비교한다. Python monkeypatch나 process 조작이 완전 차단된다고 가정하지 않고 관측 경계와 한계를 content validation에서 명시한다.

숨은 테스트 실패는 근본 개념·gate 범주·사용자에게 보이는 최소 반례를 제공한다. 전체 숨은 입력·oracle 본문은 공개하지 않는다. 상세 해설은 세션 후 opt-in으로 공개하며 이후 도움 노출 증거를 남긴다.

## 재채점과 비용

재채점은 original bundle+새 policyVersion으로 dry-run한다. 결과 변경 분포·gate 영향·예상 비용 확인 후 승인한다. 공식 채점 우선, replay/trial/AI는 낮은 priority다. 동일 owner·bundle·policy의 중복 평가 cache는 권한과 content digest를 포함한 key로만 사용할 수 있으며 CTF 플래그나 실제 Lab 관측을 사용자 간 공유하지 않는다.
