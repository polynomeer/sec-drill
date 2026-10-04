# SecDrill 보안 위협모델

보호 자산은 계정·제출물·평가 신뢰성·Oracle·서명키·Runner host·다른 Session·가용성이다. 공격자는 정상 학습자 계정, 변조 제출물, 악성 Lab 코드, 공급망 패키지, 탈취 운영 토큰을 가질 수 있다. 플랫폼의 취약 앱과 플랫폼 자체의 보안을 분리한다.

| 위협 | 경로와 영향 | 통제 | 검증과 잔여 위험 |
|---|---|---|---|
| IDOR | Session·artifact ID 추측으로 타인 소스 유출 | owner scope, scoped URL, 동일 정책 SSE | 모든 endpoint cross-owner tests; 정책 누락 위험 |
| sandbox escape | guest exploit로 host 접근 | microVM, dedicated host, no mounts, patching | egress/mount tests·외부 점검; hypervisor 0-day 잔존 |
| SSRF·egress | proxy와 취약 앱에서 외부 공격 | fixed destinations, outbound deny, synthetic targets | IPv6·DNS rebinding tests; 새 network rule 오류 |
| 평가 조작 | stdout에 가짜 결과·hidden suite 수정 | 외부 supervisor·별도 grader·signed digest | tamper mutant와 stale result tests; guest 내부 관측 한계 |
| 플래그 공유 | 다른 Session 값·정답 추출 | HMAC 세션 binding, nonce, oracle 분리 | cross-session·key rotation; 자신의 풀이 공유는 가능 |
| 서비스 거부 | fork·queue·log flood | quota, caps, fair queue, truncate | noisy neighbor 실험; host 포화 위험 |
| CSRF·XSS | Lab 출력으로 플랫폼 세션 조작 | separate origin, escaped output, CSP, CSRF | adversarial output fixtures; browser 취약점 잔존 |
| 이벤트 변조 | forged callback·replay | mTLS, job-scoped credentials, fencing, inbox | forged identity/token tests; Agent 탈취 위험 |
| 공급망 | 이미지·콘텐츠에 악성 파일 | digest pinning, signatures, SBOM, 2인 검수 | 서명 mismatch fail closed; 서명 계정 침해 |
| Prompt injection | 로그·보고서가 AI 지시를 흉내냄 | data isolation, schema parse, no tools, no score mutation | hostile prompts; 설명 오염은 완전히 제거 불가 |
| 정보 잔존 | disk·backup·로그로 PII 재노출 | ephemeral disk, TTL, tombstone reapply | delete/restore rehearsal; backup 보관기간 내 제한 |
| 내부자 오용 | 운영자가 소스·정답·키 접근 | 최소권한, break-glass, audit, 승인 분리 | 정기 권한 검토; 최고권한 악의 잔존 |

## 경계별 리뷰

Browser→API는 인증·CSRF·입력 제한, API→DB는 소유권·트랜잭션·append-only, DB→queue는 Outbox·schema, Agent→ingest는 workload identity·fencing, Lab→host는 microVM·자원·network, API→LLM은 가명화·secret omission을 검사한다.

검토할 보안 실패 사례는 다른 세션 플래그로 성공, hidden test 파일 노출, 취소 후 살아 있는 VM, 동시 재채점 두 active 결과, raw token trace 기록, Lab이 platform cookie를 받는 것이다. 하나라도 재현되면 외부 Lab 개방을 중단하고 영향 범위 확인 후 수정·재검증한다.

## 위협모델 유지

새 runtime·외부 서비스·대회·조직 공유·실제 cloud 연결은 별도 위협 리뷰를 요구한다. 위험 register에는 owner, severity, mitigation, verification, residual risk, reviewDate를 기록한다. 이 설계는 법률·규제 준수 인증이나 침투 테스트 완료를 의미하지 않는다. 실제 개인정보·상용 출시 대상 지역은 별도의 검토 항목이다.
