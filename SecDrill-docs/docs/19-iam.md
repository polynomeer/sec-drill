# SecDrill 권한과 IAM 설계

플랫폼 권한, 콘텐츠 승인 권한, 실행 워크로드 권한, 학습용 가상 IAM을 분리한다. 학습자가 Lab에서 가진 admin 권한은 플랫폼 admin 권한과 무관하다.

| 주체 | 권한 | 금지 |
|---|---|---|
| LEARNER | 본인 Session·제출·증거·리포트·내보내기 | 타인 데이터·oracle·운영 endpoint |
| AUTHOR | draft 콘텐츠 등록·검증 실행 | 자기 출판 승인·학습자 소스 기본 접근 |
| REVIEWER | 검증 보고서·정답 검토·승인 | 작성자로 참여한 버전 승인 |
| OPERATOR | Lab stop·DLQ·quarantine·배포 상태 | routine source·secret 접근 |
| SECURITY_ADMIN | 감사·제재·break-glass 승인 | 감사 기록 수정·자기 요청 단독 승인 |
| Orchestrator identity | job·quota·runner scheduling | user identity DB 직접 조회 |
| Runner identity | 자신이 lease한 job·제한 artifact·ingest | Control DB·다른 runner jobs·signing key |

## 권한 판정

기본 deny, 리소스 소유자·상태·목적을 서버가 검사한다. role만으로 모든 리포트를 공개하지 않는다. content publish는 AUTHOR != REVIEWER와 서명 검증 보고서 조건을 함께 적용한다. 운영 소스 접근은 사유·시간 제한·대상 Session·승인자의 break-glass grant가 필요하고 사용자 접근 로그에 표시한다.

MVP는 개인 owner scope만 공개한다. 이후 조직은 membership+resource orgId+share consent로 판정하고 공개 순위는 별도의 표시명 동의를 받는다. 탈퇴 시 조직 리포트 접근과 개인 기록 소유 관계를 명확히 분리한다.

## 워크로드 identity

Runner마다 짧은 mTLS 인증서와 고유 workload ID를 사용한다. bootstrap은 운영 승인된 node enrollment이고 공유 장기 bearer를 이미지에 넣지 않는다. artifact access는 jobId·digest·key-prefix·method·만료에 바인딩한다. 서명 URL은 필요한 객체 하나만 60초 허용하며 frontend URL은 매번 owner 검사를 먼저 수행한다.

guest에는 agent credentials를 주지 않는다. job revoke·node quarantine는 새로운 claim을 막고 현재 결과 token을 폐기한다. 인증서 만료 전 갱신 상태와 최소 7일 전 경보를 제공한다. 폐기된 node는 다시 enroll하기 전 신뢰하지 않는다.

## 가상 IAM 확장

가상 cloud IAM은 User·Role·Resource·Allow/Deny edge를 콘텐츠 데이터로 표현한다. 실제 cloud account를 연결하지 않는다. 평가에는 공격 경로 차단과 정상 업무 path 보존이 모두 필요하다. deny precedence·조건·role chain 의미는 자체 DSL 문서에서 정의하고 특정 클라우드 정책 엔진과 완전히 동등하다고 주장하지 않는다.
