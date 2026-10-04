# 7. T04·T06 Lab 수명과 강한 실행 격리 구현

SecDrill T04와 T06을 단계적으로 구현하라.

11-architecture, 13-state-machines, 17-sandbox-isolation,
18-threat-model, 19-iam, 25-operations-deployment를 읽어라.

먼저 선택한 runtime과 현재 host의 지원 조건을 확인하라.
실제 strong runtime 실행을 검증할 수 없으면
개발용 fake 경로와 실제 검증의 blocker를 분리해 기록하라.
격리 수준을 낮춰 완료로 처리하지 않는다.

구현 범위:

- Lab desired state와 generation
- 사용자·pool quota
- provisioning과 runtime ownership label
- 별도 origin의 인증 Gateway
- 허용 대상만 연결하는 proxy
- idle·hard TTL
- stop·kill·delete와 cleanup receipt
- 취소 경합·late callback·orphan reconciliation
- Agent의 제한된 workload identity

실제 검증:
외부 egress, metadata, Control Plane, 다른 Lab,
IPv4·IPv6·DNS 우회 접근 차단,
host mount·socket 부재,
자원·PID·출력 제한,
Control 장애 중 hard TTL,
취소 후 생성된 자원의 회수.

이 단계에서는 외부 사용자에게 공개하지 않는다.
