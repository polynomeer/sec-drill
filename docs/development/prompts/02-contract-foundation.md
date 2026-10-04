# 2. T01 공통 계약과 최소 실행 골격 구현

SecDrill T01을 구현하라.

00-common-contract, 11-architecture, 12-domain-model,
13-state-machines, 14-database, 15-api, 16-events-async,
30-implementation-plan과 contracts를 읽어라.

확정된 스택을 사용해 다음을 준비하라.

- Control Plane의 최소 실행 가능한 구조
- 공통 식별자·시각·enum·오류 봉투
- 모듈별 책임과 의존성 경계
- 계약 검증과 기본 테스트 실행 경로
- DB migration 체계
- 개발용 환경변수 예제와 health check

이번 변경에 제품 기능, 실제 Lab, AI 기능을 추가하지 않는다.
실행 영역에 JDBC나 Control DB 의존성이 들어가지 않도록
빌드 또는 구조 검사를 마련하라.

제공 SQL을 그대로 운영 migration이라고 간주하지 말고,
스키마 누락·제약·버전 호환성을 검토한 뒤 편입하라.
실제 PostgreSQL에서 migration과 주요 제약을 검사하라.

실행·검증 방법과 결과를 기록하고 작업 상태를 갱신하라.
