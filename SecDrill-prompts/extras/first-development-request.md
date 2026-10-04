# SecDrill 최초 개발 착수 요청 예제

이 저장소에서 SecDrill 개발을 시작한다.

SecDrill-docs/README.md와 다음 문서를 먼저 읽어라:
00-common-contract.md, 02-prd.md, 03-mvp-scope.md,
11-architecture.md, 13-state-machines.md,
17-sandbox-isolation.md, 20-execution-grading.md,
30-implementation-plan.md, 31-claude-code-plan.md.

contracts와 examples도 확인하라.

먼저 문서 사이의 충돌, 구현에 필요한 미결정 사항,
MVP의 주요 위험을 정리하라.
권장 기본안을 제시하되 실행 격리·인증·외부 서비스처럼
영향이 큰 결정은 임의로 확정하지 마라.

그다음 T01 범위로 저장소 기본 구조와 계약 검증을 구현하라.
아직 전체 UI, 공격 Lab, AI 기능은 만들지 마라.
필요한 검증을 실행하고 완료·미검증·다음 작업을 기록하라.
