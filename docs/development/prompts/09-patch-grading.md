# 9. T08 Python 패치 채점과 수정 증명 구현

SecDrill T08을 구현하라.

03-mvp-scope, 08-content-guide, 10-evaluation-evidence,
17-sandbox-isolation, 20-execution-grading을 읽어라.

첫 사건의 Python 패치 제출·검증을 연결하라.

- 허용 파일·경로·요청 크기 검사
- canonical bundle과 digest
- compile도 격리된 grading 환경에서 실행
- 학습 Lab와 grading 환경의 분리
- 외부 supervisor의 판정
- 필수 보안·우회·정상 회귀 gate
- EvaluationRevision과 근거 기록
- 공식 결과와 상세 공개 범위

검증 fixture:
참조 패치,
아무것도 수정하지 않은 패치,
모든 요청을 거절하는 패치,
한 endpoint만 고친 패치,
클라이언트 tenant 값을 신뢰하는 패치,
테스트·결과를 조작하는 패치.

필수 gate를 모두 통과해야 VERIFIED다.
플랫폼 오류는 SYSTEM_ERROR/INCONCLUSIVE로 처리한다.
숨은 입력·정답을 실패 메시지로 유출하지 않는다.
