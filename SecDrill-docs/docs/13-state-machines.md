# SecDrill 상태 머신

학습 진행, 실행 환경, 제출 평가를 한 상태 enum에 섞지 않는다. Session phase는 현재 활동이고 status는 생명주기다. 전이는 현재 상태·version CAS와 audit event를 함께 저장한다.

## Session

`CREATED → ACTIVE → SUBMITTED → EVALUATING → COMPLETED`

CREATED는 Lab ready 후 ACTIVE가 된다. ACTIVE에서 Lab이 만료되어도 기록은 보존되고 새 generation을 요청할 수 있다. 모드별 필수 산출물이 충족되면 finish가 SUBMITTED를 만든다. EVALUATING은 최종 리포트 생성 작업을 의미하고 단계별 채점은 ACTIVE 동안에도 수행한다. 사용자 취소는 COMPLETED 이전에 CANCELLED, hard Session 보관 정책상 종료는 EXPIRED로 간다. 최종 리포트 SYSTEM_ERROR는 EVALUATION_FAILED이며 동일 finish job을 새 attempt로 재시도할 수 있다. COMPLETED를 ACTIVE로 되돌리지 않는다.

## Lab

`REQUESTED → PROVISIONING → READY → TERMINATING → TERMINATED`

REQUESTED/PROVISIONING은 생성 실패 시 FAILED; FAILED에도 잔여 자원이 있으면 cleanup job을 실행한다. 취소·TTL·운영 중지는 모든 비종료 상태에서 TERMINATING을 요청한다. 생성 완료 callback이 취소 뒤 도착하면 READY로 전이하지 않고 그 runtime을 회수한다. TERMINATED는 런타임·네트워크·디스크 회수가 실제 확인된 상태다. cleanup 실패는 CLEANUP_FAILED로 남겨 자원을 점유한 것으로 계산하고 sweeper가 재시도한다. 활성 Lab 한도는 `cleanup_confirmed_at`이 없는 Lab으로 계산한다. TERMINATED는 이 값 없이 저장할 수 없고, 잔여 자원이 없음이 확인된 FAILED는 전이와 함께 이 값을 기록해 한도에서 제외한다.

## Job와 Submission

Job: `PENDING → DISPATCHED → LEASED → RUNNING → SUCCEEDED`.
재시도 가능 오류: LEASED/RUNNING → RETRY_WAIT → PENDING. 최대 3 attempt 소진 또는 영구 오류는 FAILED, 실행 취소 확인 후 CANCELLED. heartbeat는 LEASED부터 시작한다. PROVISION·CLEANUP job은 Lab을, GRADE job은 Submission을 가리키며 REPORT·EXPORT는 둘 다 갖지 않는다. dispatch timeout 120초는 lease 30초와 별개이며 살아 있는 worker의 대기열은 무조건 재발행하지 않는다.

Submission: `ACCEPTED → EVALUATING → EVALUATED` 또는 `EVALUATION_FAILED`. 단계별 평가가 끝나도 Session은 ACTIVE일 수 있다. 재채점은 Submission 상태를 초기화하지 않고 새 Job·EvaluationRevision을 추가한다.

## 목표와 패치

Objective: `UNATTEMPTED → ATTEMPTED → CONFIRMED`. 오답은 ATTEMPTED를 유지한다. 증거 훼손 시 새 revision에서 REVOKED로 정정하고 원래 확인 기록을 보존한다.

Patch gate: `NOT_VERIFIED`, `VERIFIED`, `INCONCLUSIVE`. 검증 전 기본은 INCONCLUSIVE이며 필수 보안·정상 gate를 전부 통과해야 VERIFIED다. 플랫폼 실패는 INCONCLUSIVE로 남긴다.

## 전이 충돌 예

ACTIVE Session에서 version 8 액션과 version 8 finish가 동시에 오면 한 작업만 CAS 성공한다. 실패한 작업은 409와 최신 version을 반환한다. CANCELLED Job의 오래된 result는 현재 fencing token과 상태 검사로 거절한다. 사용자 화면의 seq가 늦어도 서버 상태가 진실이다. 동일 입력의 idempotency 재요청은 CAS를 다시 수행하지 않고 최초 응답을 돌려준다.
