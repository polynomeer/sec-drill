# SecDrill 운영과 배포 설계

외부 파일럿은 앱 호스트와 강한 격리 Runner 호스트를 분리하고 인터넷 공개 포트는 Web·API·Lab Gateway로 제한한다. DB·broker·store 관리·Runner ingress는 공개하지 않는다.

## 환경과 배포

dev는 신뢰된 로컬 compose·fake runner, staging은 운영과 같은 강한 runtime·합성 콘텐츠, pilot는 제한 가입과 20 Labs 한도다. IaC로 네트워크·service identity·store policy·resource quotas를 관리한다. secret은 vault/KMS 계열 저장소에서 workload identity로 주입하며 이미지·문서에 실제 값을 넣지 않는다.

CI 흐름은 lint/type → unit/domain → API/schema → DB migration → content validation → sandbox adversarial tests → image SBOM/signature → staging E2E → canary → rollout이다. 취약 Lab image의 의도된 결함은 manifest waiver에 명시하고 Control/Agent/Gateway 이미지의 동일 결함을 허용하지 않는다. signing과 publish 권한은 CI의 최소 단계에만 부여한다.

rollback은 이전 Control API image와 호환 schema로 되돌리고 실행 중 Session은 frozen imageDigest를 유지한다. 신규 lab request를 잠시 drain하고 Agent는 기존 job lease를 종료하거나 안전하게 완료한다. destructive DB rollback 대신 호환 forward migration을 우선한다.

## 백업과 복구

초기 목표 가정은 Control DB RPO15분·RTO2시간이다. DB PITR 또는 정기 incremental, private artifact versioning, 서명키 복구 계획을 별도 관리한다. 매월 실제 임시 환경에서 restore·로그인·한 제출 평가·원장 검증을 수행한다. ephemeral Lab disk는 백업하지 않는다. 복원 뒤 deletion tombstone·revoked identity·content quarantine를 먼저 재적용한다.

## Runbook

| 사건 | 즉시 행동 | 복구와 종료 기준 |
|---|---|---|
| broker 장애 | 신규 제출 수락은 Outbox 용량 내 유지; 대기 안내 | broker 회복·publisher 재개·inbox 중복 검사 |
| Runner 유실 | node quarantine, lease 만료 확인, late token 거절 | clean host enroll·job 재할당·orphan 회수 |
| Lab escape 의심 | 신규 Lab 중지·host network 격리·증거 보존 | host 재이미징·credentials revoke·범위 확인·격리 재검증 |
| 판정 오류 | content/policy 비활성화·영향 Session 목록 | dry-run 재채점·승인·새 revision·사용자 변경 안내 |
| orphan 증가 | quota 증설 중지·runtime label reconciliation | 자원 회수 receipt·원인 수정·5분 미만 회수 회복 |
| 원장 mismatch | 해당 리포트 검증 표시 중단·쓰기 경로 조사 | DB checkpoint·artifact 검증·정정 증거 기록 |

## 삭제와 abuse

사용자 삭제 요청은 본인 재인증 → scope 확인 → 접속 revoke → Lab 종료 → 객체·identity 제거 → projection invalidate → 완료 receipt 순서다. 운영 목표는 7일 내 완료 가정이고 지역별 실제 의무는 별도 검토한다. abuse 대응은 rate-limit·Lab stop·임시 제출 제한과 이의 기록을 제공한다. 무제한 자동 IP 차단으로 합법적 사용자 전체를 막지 않는다.

배포 승인 기록은 담당자·version/digest·검증 결과·rollback 경로·경보 수신자를 포함한다. 실제 운영 경보 수신자가 지정되지 않은 파일럿은 시작하지 않는다.
