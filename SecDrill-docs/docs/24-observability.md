# SecDrill Observability 설계

플랫폼 운영 telemetry와 학습 사건 telemetry는 서로 다른 데이터다. 학습자는 해당 Lab의 합성 로그를 보고 운영자는 job·host·queue 건강을 본다. 운영 traces에 사용자 소스·플래그·토큰을 남기지 않는다.

## 지표와 경보

| 영역 | 지표 | 초기 경보 가정 |
|---|---|---|
| API | request latency, 5xx rate, auth failures | 5분 5xx>1% 또는 p95>300ms 10분 |
| Outbox/queue | oldest age, backlog, publish failures | official oldest>60초 5분; publisher 1분 무진행 |
| Runner | busy slots, heartbeat age, host RAM, quarantine | heartbeat>30초·OOM·탈출 의심 즉시 |
| Lab | provisioning latency, active count, orphan age | p95 ready>60초 10분; orphan>5분 즉시 |
| Grading | latency by class, SYSTEM_ERROR rate, stale result | SYSTEM_ERROR>2% 10분; stale 급증 |
| Evidence | append failures, seq gaps, checkpoint mismatch | 공식 evidence 실패·hash mismatch 즉시 |
| Store | latency, missing digest, orphan bytes | digest mismatch 즉시; PUT 실패>1% |
| 개인정보 | deletion backlog, expired raw artifacts | 승인 삭제 목표 초과·TTL sweep 실패 |

traceId·requestId·jobId·session pseudonym을 상관관계로 연결한다. sessionId를 metric label로 사용해 고 cardinality를 만들지 않는다. span은 accept→outbox→dispatch→claim→compile→test→ingest→evaluation 순으로 이어진다. 서로 다른 메시지는 correlationId·causationId로 연결한다.

## 구조화 로그

필드는 timestamp, service, level, requestId, jobId, phase, errorCode, duration, attempt다. 요청 content·flag·auth header·signed URL query는 로깅 금지한다. raw Lab 로그는 별도 암호화 store에 owner 권한으로 저장하고 운영자의 기본 검색에 섞지 않는다. 비밀 탐지 redaction 실패는 원본을 운영 로그에 남기기보다 해당 필드 drop을 우선한다.

## 운영 화면

큐별 backlog와 오래된 작업, Lab 상태·실제 runtime reconciliation, 실패 종류·DLQ reason, 최근 배포·content digest, 회수 지연, 평가 정책별 pass 분포를 제공한다. 갑자기 pass율이 올라가면 사용자 실력 향상뿐 아니라 hidden gate 누락·content 오류를 검토한다.

## SLO와 비용

외부 파일럿 Control Plane 월 가용성 목표는 99.5%로 제안하며 계획된 시험·파일럿 중단 조건은 명시한다. 공식 채점·Lab provisioning은 API 가용성과 별도의 SLI다. 비용은 runner allocated CPU/RAM 시간+object bytes+optional AI tokens를 Session별 가명 식별자로 집계한다. 실제 요금은 provider 견적과 측정으로 정하고 단가를 임의 확정하지 않는다.
