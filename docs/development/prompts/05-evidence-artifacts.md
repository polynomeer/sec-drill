# 5. T09 Evidence Ledger와 Artifact 기반 구현

SecDrill T09의 기반을 구현하라.

10-evaluation-evidence, 14-database, 16-events-async,
22-replay와 개인정보·삭제 관련 문서를 읽어라.

다음을 구현하라.

- Session별 seq와 head 갱신
- canonical payload digest와 hash chain
- 증거 source·trustLevel 구분
- 일반 앱 권한의 append-only 제약
- private Artifact 저장·digest·owner 검사
- raw payload와 원장 metadata 분리
- 보관기간과 삭제 처리의 기초 계약

공식 판정과 사용자 주장을 같은 신뢰 수준으로 저장하지 않는다.
raw flag·token·secret·사용자 소스를 운영 로그에 남기지 않는다.

동시 append, 중복 이벤트, 순서, 해시 검증,
다른 Session Artifact 연결, 접근 권한을 테스트하라.

개인정보 삭제의 전용 역할·승인·감사 경로를 검토하라.
실제 삭제 구현이 아직 없다면 완료로 표시하지 않는다.
