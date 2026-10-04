# SecDrill ADR 초안 목록

아래 결정은 모두 Proposed다. 구현 spike와 담당자 리뷰 후 Accepted/Rejected/Superseded 상태로 별도 ADR 파일에 옮긴다. 기존 제품의 결정과 SecDrill의 위험을 혼동하지 않는다.

| ID | 맥락과 제안 | 대안과 비용 | 수용·재검토 기준 |
|---|---|---|---|
| ADR-001 | Control은 modular monolith, 실행은 분리 | full microservices는 운영 비용; 한 process는 신뢰 경계 부족 | 모듈 경계 빌드 검사; scale 병목 시 분리 |
| ADR-002 | 공격 Lab는 Session microVM | rootless container는 적대적 격리 부족; gVisor는 호환 검증 필요 | runtime spike·egress·host test; 과제 compatibility 재검토 |
| ADR-003 | DB Outbox+RabbitMQ durable delivery | Redis Streams 가능; 직접 push는 commit gap | 장애·중복·backlog 테스트; 운영 부담 비교 |
| ADR-004 | server-managed lease+fencing | lock만으로 stale callback 차단 부족 | worker kill·late result; multi-node 확장 검증 |
| ADR-005 | 공식 점수는 rule/hidden gates | AI 점수는 재현성과 injection 위험 | 동일 bundle deterministic 판정; 회고 의미 평가는 별도 |
| ADR-006 | Ledger append-only+privacy artifact 분리 | 완전 원문 영구 저장은 삭제 곤란 | 삭제/restore·hash checkpoint 검증 |
| ADR-007 | Replay는 simulated reducer와 observed log 분리 | VM rewind는 비용·비결정성 | seek parity·gap 표시; memory rewind 요구 시 재검토 |
| ADR-008 | CTF와 skill score 분리 | 하나의 점수는 도움·탐지 능력 혼동 | UI 사용자 이해도·Transfer 결과 |
| ADR-009 | OIDC login+opaque sessions | 자체 password·JWT 가능 | provider·logout·CSRF·refresh reuse spike; 아직 provider 미선정 |
| ADR-010 | signed versioned content bundles | DB script는 저작 편의, 공급망 경계 약함 | digest·2인 승인·immutable publish |
| ADR-011 | real Lab와 deterministic IR 결합 | 전체 digital twin은 비용·과장 | 관측/모델 UI 구분·콘텐츠 인과성 검수 |
| ADR-012 | SSE 우선, terminal만 websocket | polling 단순; full websocket 운영 복잡 | reconnect cursor·cookie auth·connection 부하 |
| ADR-013 | skill은 versioned projection | 조회 시 재계산은 단순하지만 향후 비용 증가 | watermark·rebuild parity; 초기 read compute 허용 |
| ADR-014 | 전이는 새 Session과 다른 계열 | 같은 seed 재시도는 개념 암기 구분 어려움 | 도움 노출 전파·독립 성공 calibration |
| ADR-015 | dependency download 없는 Python patch MVP | 다언어·임의 repo는 비용과 공급망 증가 | compile 이미지·allowlist·콘텐츠 검증 |

## ADR 템플릿

제목, 상태, 날짜, 담당자, 문제/제약, 선택, 비교 대안, 비용·위험, 검증 증거, 결과, 되돌리는 조건, 영향을 받는 문서·계약·테스트를 포함한다. 아직 검증하지 않은 조건은 미검증으로 표시한다. 제품명 변경처럼 동작을 바꾸지 않는 표기 수정은 ADR 없이 문서 변경으로 처리할 수 있다.
