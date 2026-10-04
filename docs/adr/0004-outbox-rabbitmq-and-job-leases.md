# 0004 Outbox·RabbitMQ 전달과 server-managed job lease

- 상태: Proposed (D-08 RabbitMQ는 소유자 결정, 운영 부담·장애 실험 전 재검토)
- 날짜: 2026-10-04
- 담당: 프로젝트 소유자
- 원 초안: ADR-003(DB Outbox + RabbitMQ), ADR-004(lease + fencing)
- 관련: T05, FR-06, NFR-02, [13 상태](../../SecDrill-docs/docs/13-state-machines.md), [16 이벤트](../../SecDrill-docs/docs/16-events-async.md), [20 채점](../../SecDrill-docs/docs/20-execution-grading.md)

## 문제와 제약

제출 수락과 이벤트를 원자적으로 남기고, broker·worker·네트워크 장애에서도 결과가 사라지거나 두 번 반영되지 않아야 한다. exactly-once 전달은 가정하지 않는다. 플랫폼 실패를 학습자 FAIL로 바꾸면 안 된다.

## 선택

- **수락 트랜잭션**: Idempotency 확인(advisory lock) → owner guard → Session version CAS → Submission → GRADE job(PENDING) → Evidence → Outbox → replay 응답 저장이 한 transaction. raw 제출 내용은 저장하지 않고 digest만 남긴다(content 저장은 T09).
- **Idempotency**: owner + 실제 경로 + key, 24시간. 같은 digest는 첫 응답을 byte 그대로 반환, 다른 digest는 409. 2xx만 저장해 실패한 요청은 같은 key로 재시도할 수 있다.
- **Publisher**: Outbox row를 `FOR UPDATE SKIP LOCKED`로 가져와 publisher confirm(correlated) + mandatory로 발행한다. ack이고 반환되지 않은 경우만 published. 실패는 지수 backoff(최대 1분).
- **Topology**: topic exchange `secdrill.events`, quorum queue `grading.official`(delivery limit 3, DLX `secdrill.dlx` → `secdrill.dlq`).
- **Consumer**: inbox 행과 업무 변경을 같은 transaction으로 commit한 뒤 ack. poison(형식·schemaVersion·id 오류)은 즉시 DLQ, 일시 오류는 requeue 후 delivery limit에서 DLQ.
- **Job lease**: Control Plane이 lease를 관리한다(pull). claim 시 attempt·fencing token 증가, lease 30초. start·heartbeat·complete는 현재 token, 올바른 상태, 미만료 lease에서만 수락하고 그 외는 audit 후 STALE.
- **재시도**: PLATFORM_ERROR·lease 만료는 총 3 attempt(지연 5초·20초 + 최대 1초 jitter) 뒤 FAILED + SYSTEM_ERROR evaluation. CONTENT_INVALID는 즉시 SYSTEM_ERROR. dispatch 대기 120초 초과는 lease와 별개로 표시·감사만 한다.
- **fake worker**: `:execution:fake-worker`는 protocol만 의존한다. 결과는 policy `fake-worker/0`, `dimensions.fake=true`, Evidence trust `SIMULATED`, 기본 verdict FAIL. `local` profile 밖이나 `prod`와 함께 켜면 기동 실패.

## 비교한 대안

- Redis Streams: 구성 요소가 줄지만 confirm·delivery count·DLQ를 앱에서 구현해야 한다.
- 메시지 push로 worker에 작업 전달: worker identity·mTLS(T06) 전에는 lease 소유를 서버가 판정하기 어렵다.
- 공통 broker transaction(XA): 운영 복잡도와 성능 비용이 크고 exactly-once를 보장하지도 않는다.

## 비용과 위험

- publisher는 confirm을 기다리는 동안 row lock을 쥔다. 배치 크기·timeout은 측정 전 가정이다(27).
- confirm을 놓친 메시지는 나중에 도착할 수 있어 중복 발행이 정상 경로다. 모든 consumer는 inbox를 써야 한다.
- 현재 Orchestrator 역할(dispatch consumer·sweeper)은 Control Plane 안에 있다. `execution/orchestrator`로 분리할 때 DB 접근을 internal API로 바꿔야 한다(T06).
- 2 KiB 이상 출력·artifact 전달, quota·fair queue, DLQ redrive 도구는 아직 없다.

## 검증 증거

`./gradlew check`(macOS, Docker, PostgreSQL 18.6, RabbitMQ 4.3.6 digest 고정):

- `SubmissionAcceptanceTest` 6건: 원자 저장, raw flag 미저장, replay(멤버 순서 무관), 다른 body 409, stale version 409와 key 재사용, 동시 4요청 1건
- `CrashBeforeCommitTest`: commit 직전 장애 시 submission·job·evidence·head·outbox·idempotency 모두 없음
- `OutboxDeliveryTest` 4건: confirm 후 published, broker pause 중 row 보존과 재발행, 중복 전달 시 job 변경 1회, poison DLQ
- `JobLeaseTest` 7건: fake 결과 라벨, 만료 lease 결과 거절·감사, fencing token 증가 후 이전 worker 거절, heartbeat 연장, 플랫폼 오류 3회 → SYSTEM_ERROR(FAIL 없음), CONTENT_INVALID 즉시 실패와 patch gate INCONCLUSIVE, lease 3회 만료, dispatch timeout 표시
- `FakeWorkerSafetyTest` 2건
- 개발 중 발견: replay 응답을 jsonb에 저장해 key 순서가 바뀌던 결함(V3에서 text로 변경), broker pause 테스트의 잘못된 기대(늦은 전달은 정상)
- 미검증: 다중 Control Plane 인스턴스, broker cluster·네트워크 분할, 부하, DLQ redrive

## 결과와 되돌리는 조건

장애·backlog 실험(27)에서 운영 부담이 크거나 quorum queue 동작이 요구를 못 맞추면 broker를 재검토한다. Orchestrator 분리 시 lease API를 mTLS internal endpoint로 옮긴다.

## 영향을 받는 문서·계약·테스트

`V3__job_execution.sql`, 팩 14·16, `:control-plane:{platform,evidence,submission}`, `:execution:{protocol,fake-worker}`, 위 테스트, [T05](../development/T05.md)
