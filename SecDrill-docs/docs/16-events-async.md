# SecDrill 이벤트와 비동기 처리 명세

업무 이벤트는 사실 기록이고 실행 명령은 작업 요청이다. 두 종류 모두 버전 봉투를 사용하지만 소비자가 eventId와 jobId를 각각 멱등 처리한다. [event.schema.json](../contracts/event.schema.json)은 공통 이벤트 봉투를 정의한다.

## 주요 이벤트

| type | producer | consumer | payload |
|---|---|---|---|
| SessionCreated | Session | 추천·분석 | sessionId,versionId,mode |
| LabRequested | Lab | Orchestrator | labId,generation,templateDigest |
| LabReady | Result Ingest | Session·Gateway | labId,generation,runtimeRef |
| SubmissionAccepted | Submission | Orchestrator | submissionId,jobId,kind,bundleRef |
| ExecutionCompleted | Result Ingest | Evaluation | jobId,attempt,fencingToken,resultDigest |
| EvaluationCommitted | Evaluation | Report·SkillProjection | submissionId,revision,evidenceIds |
| ActionApplied | Simulator | Ledger·Replay | actionId,tick,stateDigest |
| LabTerminationRequested | Lab | Runner | labId,generation |
| LabTerminated | Runner ingest | Quota·Ops | labId,generation,cleanupReceipt |
| SessionFinished | Session | Report·추천 | sessionId,evaluationRefs |

## 전달과 중복

DB transaction에서 row와 outbox를 함께 저장한다. Publisher는 batch claim·broker publish confirm 후 publishedAt을 기록한다. publish 이후 DB 갱신 전 죽으면 재발행되므로 소비자는 consumer_inbox unique(consumer,eventId)를 사용한다. DB 상태를 반영하는 소비자는 inbox insert와 업무 변경을 같은 transaction에 수행하고 commit 후 ack한다.

Lab 생성처럼 외부 side effect는 inbox만으로 exactly-once가 되지 않는다. `labId:generation` runtime label과 durable desired state를 먼저 저장하고 reconciler가 실제 자원과 비교한다. 재시도는 같은 label의 자원을 찾으며 완료 또는 정리를 확정한다. 점수·quota는 관측된 runtime 완료 기록을 기준으로 한 번만 갱신한다.

## 순서와 임대

모든 이벤트의 global 순서를 보장하지 않는다. Session별 seq와 aggregateVersion으로 순서를 확인하고 누락·역순은 DB 원본을 조회한다. Simulator 액션은 per-session lock 또는 actor로 직렬화한다. 워커 claim마다 fencing token이 증가하고 result는 현재 token+RUNNING 상태가 일치할 때만 반영한다.

heartbeat 10초, lease 30초, 전체 실행 timeout 300초다. dispatch 대기는 lease가 아니며 별도 timeout 120초와 pool liveness를 확인한다. lease가 만료되어 재할당된 뒤 이전 워커가 보낸 결과는 감사만 남긴다. retry는 최대 총 3 attempt, 지연 5초·20초·지터이며 사용자 FAIL은 재시도하지 않는다.

## 큐와 DLQ

queue는 lab.lifecycle, grading.official, replay.optional, coaching.optional로 나눈다. Lab cleanup과 공식 채점이 우선이고 추천·AI는 낮은 우선순위다. 영구 schema 오류·서명 불일치는 즉시 DLQ, 일시 인프라 오류는 retry 소진 후 DLQ다. DLQ redrive는 원래 eventId/jobId와 새 attempt·감사 목적을 유지한다. 결함 있는 payload를 그대로 무한 재시도하지 않는다.

## 호환성

schemaVersion 추가 필드는 optional로 도입하고 breaking change는 새 major type으로 병행한다. 소비자는 알 수 없는 type을 무조건 ack하지 않고 quarantine에 넣는다. payload에는 전체 소스·토큰·PII를 넣지 않고 digest와 scoped ref만 전달한다. 큐 복구 시 outbox backlog·최고 미처리 시간·DB job reconciliation을 함께 확인한다.
