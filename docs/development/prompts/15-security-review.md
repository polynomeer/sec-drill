# 15. 보안과 채점 신뢰성 집중 리뷰

현재 구현을 보안과 채점 신뢰성 관점에서 리뷰하라.

17-sandbox-isolation, 18-threat-model,
19-iam, 20-execution-grading을 기준으로 검사하라.

우선순위:
owner guard 누락,
Artifact·SSE·Replay 접근,
Lab Gateway destination 검증,
secret·oracle 노출,
compile/실행 격리,
test tampering,
result spoofing·stale fencing,
취소 후 살아 있는 자원,
quota 우회,
CSRF·출력 XSS,
서명·콘텐츠 공급망 경계.

검증 가능한 문제는 합성 로컬·staging 환경에서
실패 재현 테스트를 만들고 수정하라.
실제 외부 대상에는 공격을 수행하지 않는다.

각 발견에 위치·재현 조건·영향·수정·검증을 기록하라.
강한 runtime을 실제로 검사하지 못한 항목은
안전하다고 결론 내리지 말고 미검증으로 남겨라.
