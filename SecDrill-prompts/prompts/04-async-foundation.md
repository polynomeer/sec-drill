# 4. T05 신뢰할 수 있는 비동기 작업 기반 구현

SecDrill T05를 구현하라.

13-state-machines, 14-database, 16-events-async,
20-execution-grading과 이벤트 계약을 읽어라.

다음을 작은 변경 단위로 구현하라.

- Submission과 Outbox의 원자 저장
- Idempotency-Key와 요청 digest 검사
- Publisher와 broker 확인
- Consumer inbox와 중복 처리
- Job 상태·dispatch timeout·lease
- heartbeat와 fencing token
- 제한된 재시도·DLQ·감사 기록

이 작업에서는 실행을 fake worker로 연결한다.
fake와 실제 실행 결과는 명확히 구별한다.

필수 검증:
동일 키·동일 입력은 같은 응답,
동일 키·다른 입력은409,
커밋 전 장애는 둘 다 저장되지 않음,
커밋 후 broker 장애는 Outbox 보존,
중복 이벤트는 업무 변경을 중복 수행하지 않음,
만료된 lease의 결과는 공식 판정에 반영되지 않음.

메시지의 exactly-once 전달을 가정하지 않는다.
플랫폼 실패를 사용자 실패로 바꾸지 않는다.
