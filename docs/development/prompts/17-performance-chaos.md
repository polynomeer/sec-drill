# 17. 성능·장애·카오스 검증

27-performance-chaos의 계획을 현재 구현에 적용하라.

먼저 실제 하드웨어·runtime·버전·자원 예약과
사용 가능한 staging 환경을 기록하라.
문서의 성능 가정을 측정 결과처럼 사용하지 않는다.

baseline→정상 부하→증가 부하→soak 순으로 측정하고,
다음 장애를 안전한 합성 환경에 주입하라.

- publish 직후 process 종료
- 실행 중 Runner 유실
- broker·DB·store 일시 장애
- 중복·역순·오래된 result
- provisioning callback 유실
- noisy neighbor와 output flood
- Control 장애 중 hard TTL

latency를 queue·provision·compile·test·ingest로 분해하라.
불변식 위반, 중단 조건, 회복 시간, orphan·DLQ 잔여를 검사하라.

재현 가능한 결과 보고서를 만들고
목표 미달의 원인을 수정한 뒤 해당 검사를 재실행하라.
운영 사용자에게 영향이 있는 실험은 승인 없이 하지 않는다.
