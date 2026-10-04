# SecDrill 성능과 장애와 카오스 테스트 계획

성능 목표는 지정 조건에서 검증하며 사용자 코드의 무제한 실행을 기준으로 하지 않는다. 아래 하드웨어·분포는 측정 시작 가정이고 운영 host 선택 후 결과를 첨부한다.

## 기준 워크로드

Control 4vCPU/8GiB, DB4vCPU/8GiB SSD, Runner 합계64vCPU/128GiB·적어도 2호스트를 가정한다. 20 Labs의 예약40vCPU/40GiB에 compile·채점 peak와 host 여유를 별도로 확보한다. oversubscription 여부를 실측 전에 명시하고 특정 host 성능을 이미 확보했다고 주장하지 않는다.

50 학습자 중 20 실제 Lab, 나머지는 카탈로그·리포트·대기. 사용자당 평균 REST 0.2rps, 활성 Lab당 합성 API 5rps, 전체 공식 제출 분당5개, SSE50 연결, Session당 evidence 최고1,000events/min(압축 요약 후)을 기준으로 한다. 5분 ramp·30분 steady·5분 cooldown 후 2시간 soak를 수행한다.

## 측정

API p95<=300ms(스토어 download·SSE 제외), Lab request→READY p95<=60초(정상 capacity), 짧은 표준 패치 accepted→evaluation p95<=30초, SYSTEM_ERROR<1%, stale 결과 반영0건, orphan 회수<=5분을 목표로 한다. queue wait·provision·compile·test·ingest를 분해한다. 1×·2×·3× 부하에서 공정 대기·429·CPU·RAM·store 성장·회수 지연을 기록하며 3×에서도 성공 latency를 무조건 유지하겠다고 약속하지 않는다.

## 장애 실험

| 실험 | 주입 위치 | 기대 불변식 | 회복 목표 가정 |
|---|---|---|---|
| publish 직후 process kill | Publisher | duplicate는 가능, submission 유실 없음 | 2분 내 backlog 재개 |
| test 중 Agent kill | Runner | lease 회수·late token 차단 | 60초 내 재할당 판단 |
| broker 60초 단절 | 제어 네트워크 | Outbox 보존·무한 API 재시도 없음 | 5분 내 대기 소진 |
| store GET 실패 | grading input | SYSTEM_ERROR; 사용자 FAIL 아님 | 최대3attempt 후 명시 오류 |
| result duplicate/역순 | ingest | active evaluation 1개 | 즉시 중복 거절 |
| DB 30초 단절 | API·ingest | 미커밋 성공 응답 없음 | 복구 후 job reconciliation |
| create 후 callback 유실 | lab lifecycle | runtime label로 orphan 인식 | 5분 이내 회수/연결 |
| noisy neighbor output/memory | Lab | 다른 Session·host 보호 | 상한 적용·해당 Lab 종료 |
| Control 다운 상태 TTL | Agent | hard TTL 자율 종료 | TTL+1분 이내 차단 |
| clock skew ±30초 | collector | 순서는 seq; occurredAt 불확실 표시 | 평가 타임스탬프 오판 없음 |

## 안전한 실행과 중단

staging 합성 환경에서만 주입하고 실제 사용자 Session이 있는 pilot에는 별도 승인된 창으로 한정한다. 실험 범위·duration·rollback·담당자를 기록한다. 5xx>5% 2분, host RAM>90%, cleanup 지연>5분, cross-session 영향은 즉시 중단 조건이다. 중단 뒤에도 orphan·DLQ·pending 삭제를 끝까지 확인한다.

결과 보고는 가정·환경·버전·그래프·불변식 검사·실패 원인·개선·재측정으로 작성한다. 이번 문서 작성에서 성능/카오스 시험을 실행한 것은 아니다.
