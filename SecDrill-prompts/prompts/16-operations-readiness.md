# 16. T14 운영·배포·복구·개인정보 처리 준비

SecDrill T14의 운영 기반을 구현하라.

24-observability, 25-operations-deployment,
14-database, 19-iam을 읽어라.

다음을 준비하고 staging에서 검증하라.

- API·queue·Runner·Lab·grading 지표
- 개인정보 없는 구조화 로그와 tracing
- 경보와 실제 수신자 설정
- 이미지·콘텐츠 서명과 환경 분리
- CI와 staging 검증
- drain·rollback·node quarantine
- DB·Artifact 백업과 실제 복원
- 사용자 export·삭제·보관기간 sweep
- 복원 뒤 deletion tombstone 재적용
- orphan·DLQ·판정 오류 Runbook

외부 계정·비용이 필요한 작업은
구체적인 변경·비용·복구 방법을 준비한 뒤 승인을 요청하라.
승인 없이 실제 외부 배포를 수행하지 않는다.

절차 문서만 작성한 항목과 리허설한 항목을 구분하라.
