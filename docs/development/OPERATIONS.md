# SecDrill 운영 절차 (T16)

마지막 갱신: 2026-10-07. 이 문서는 **절차(procedure)**다. 아래 항목은 staging·cloud·외부 경보 수신자·서명 인프라가 있어야 **리허설**이 성립하므로, 여기서는 수행 방법만 정의하고 실제 수행/리허설은 하지 않았다. 구현·리허설된 운영 기능은 [T16](T16.md)에 있다. 기준: [25-operations-deployment](../../SecDrill-docs/docs/25-operations-deployment.md), [24-observability](../../SecDrill-docs/docs/24-observability.md).

상태 표기: **구현** = 코드+테스트, **절차** = 문서만(미리허설).

## 환경과 배포 (절차)

- dev(로컬 compose·fake runner) / staging(운영과 같은 강한 runtime·합성 콘텐츠) / pilot(제한 가입·20 Lab)로 분리한다. 기동 시 안전장치는 **구현**됨: `AuthSafetyCheck`(prod는 OIDC+https origin, dev-login 금지), `LabSafetyCheck`(prod는 connect key+별도 origin gateway, 미검증 격리 금지), `ArtifactStoreSafetyCheck`(prod에 로컬 store 금지).
- 네트워크·service identity·store policy·quota는 IaC로 관리한다(절차). secret은 vault/KMS에서 workload identity로 주입하고 이미지·문서에 실제 값을 넣지 않는다 — 저장소 규칙으로 강제(합성 데이터만).
- 공개 포트는 Web·API·Lab Gateway로 제한하고 DB·broker·store·Runner ingress는 공개하지 않는다. 관측 스크래프는 보안 관리 포트/네트워크에서만(지표는 learner 체인에 노출하지 않음 — **구현**).

## 이미지·콘텐츠 서명 (일부 구현)

- 콘텐츠 번들 서명·2인 승인·출판 게이트는 **구현**됨(ADR 0006, `ContentValidation`·`ContentPublishingTest`). 서명 불일치는 fail closed.
- Control/Agent/Gateway 이미지 SBOM·서명, registry 서명 검증(D-16), 취약 Lab 이미지의 의도된 결함 waiver는 **절차**(배포 파이프라인). 서명·출판 권한은 CI 최소 단계에만 부여한다.

## CI와 staging (절차)

- 파이프라인: lint/type → unit/domain → API/schema(`scripts/check.py --strict`) → DB migration(V1..Vn==schema.sql) → content validation → sandbox adversarial → image SBOM/signature → staging E2E → canary → rollout.
- 현재 GitHub CI(`check`·`build`)는 **미실행**(push 안 함). Linux 호스트 Testcontainers·canary·rollout은 push·staging 후 확인.

## drain·rollback·quarantine

- **Lab pool drain(구현)**: `POST /ops/v1/lab-pool/drain {draining:true}` → 신규 Lab 거절(503), 기존 Lab·lease 유지. 재개는 `{draining:false}`.
- **Runner quarantine(구현)**: `POST /ops/v1/runners/{id}/quarantine {reason}` → 새 claim 차단·start/heartbeat/complete STALE(결과 token 폐기)·lease 만료 후 재배정. 재-enroll 후 `.../release`.
- **rollback(절차)**: 이전 Control API 이미지 + 호환 schema로 되돌린다. 실행 중 Session은 frozen imageDigest 유지. 먼저 drain으로 신규 Lab을 멈추고 Agent는 기존 lease를 안전 종료/완료. destructive DB rollback 대신 호환 forward migration 우선.

## 백업과 복구 (일부 구현)

- 가정: Control DB RPO 15분·RTO 2시간. DB PITR/정기 incremental, private artifact versioning(D-15 미구현), 서명키 복구 계획은 **절차**.
- 월간 복원 리허설(절차): 임시 환경에 restore → 로그인 → 한 제출 평가 → 원장 검증. ephemeral Lab disk는 백업하지 않는다.
- **복원 뒤 tombstone 재적용은 구현**됨: `reapply_tombstones`로 삭제된 주체를 가장 먼저 다시 지운다([ADR 0013](../adr/0013-privacy-data-subject-execution.md), `PrivacyExecutionTest`). revoked identity·content quarantine 재적용은 절차(기동 순서).

## 관측과 경보

- **지표(구현)**: `OperationalMetrics` 게이지(outbox backlog·oldest age·publish failures, labs active·cleanup pending, grading SYSTEM_ERROR, jobs dispatch timeout, runners quarantined) + actuator HTTP 서버 지표. 상관 id `X-Request-Id`(**구현**).
- **구조화 로그(절차)**: 금지 필드(요청 content·flag·auth header·signed URL query) 미기록은 지켜짐(T15). JSON 인코더(logback) 필드: timestamp·service·level·requestId·jobId·phase·errorCode·duration·attempt — 배포 로그 설정.
- **경보 규칙·수신자(절차)**: 24 표의 임계값(예: 5분 5xx>1% 또는 p95>300ms 10분; official outbox oldest>60초 5분; runner heartbeat>30초; orphan>5분; grading SYSTEM_ERROR>2% 10분; 승인 삭제 backlog·TTL sweep 실패). **실제 운영 경보 수신자가 지정되지 않은 파일럿은 시작하지 않는다.**

## Runbook (절차)

| 사건 | 즉시 행동 | 복구·종료 기준 | 현재 수단 |
|---|---|---|---|
| broker 장애 | 신규 제출은 Outbox 용량 내 유지, 대기 안내 | broker 회복·publisher 재개·inbox 중복 검사 | Outbox/inbox **구현**(T05); publish failures 게이지 |
| Runner 유실 | `quarantine` → lease 만료 확인·late token 거절 | clean host 재enroll·job 재할당·orphan 회수 | quarantine **구현**; `labs.cleanup.pending` 게이지 |
| Lab escape 의심 | 신규 Lab `drain` 중지·host network 격리·증거 보존 | host 재이미징·credential revoke·범위 확인·격리 재검증 | drain **구현**; 강한 격리 검증은 D-10 |
| 판정 오류 | content/policy 비활성화(`quarantine` scenario)·영향 Session 목록 | dry-run 재채점·승인·새 revision·사용자 안내 | scenario quarantine·rejudge **구현**(dry-run은 절차) |
| orphan 증가 | quota 증설 중지·runtime label reconciliation | 회수 receipt·원인 수정·5분 미만 회수 회복 | sweeper·reconcile·`reclaimExpiredGrading` **구현** |
| 원장 mismatch | 해당 리포트 검증 표시 중단·쓰기 경로 조사 | DB checkpoint·artifact 검증·정정 증거 기록 | `LedgerVerifier` **구현**; 표시 중단은 절차 |
| DLQ 적체 | 원인 분류·재처리 | 재처리·원인 수정 | inbox 멱등 **구현**; 재처리 UI/엔드포인트 **절차** |

## 삭제와 abuse

- 사용자 삭제(재인증→scope→revoke→Lab 종료→객체·identity 제거→projection 무효화→receipt)는 **구현**([ADR 0013](../adr/0013-privacy-data-subject-execution.md)). 전용 `privacy_eraser` 역할·2인 승인·tombstone·감사 포함. 진짜 재인증(D-09)·배포 전용 접속(D-14)은 남음.
- abuse: rate-limit·Lab stop·임시 제출 제한·이의 기록은 **절차**(미구현). 무제한 자동 IP 차단 금지.

## 배포 승인 기록 (절차)

담당자·version/digest·검증 결과·rollback 경로·경보 수신자를 기록한다. 실제 경보 수신자 미지정 파일럿은 시작하지 않는다.
