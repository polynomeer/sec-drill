# 개인정보 삭제 경로 검토

상태: **검토와 기초 계약만 완료. 실제 삭제 실행은 미구현이며 FR-10 출시 게이트를 통과하지 못한다.** 검토일 2026-10-04. 근거: [14](../../SecDrill-docs/docs/14-database.md) "보관과 개인정보 삭제", [15](../../SecDrill-docs/docs/15-api.md) `POST /deletion-requests`, [19](../../SecDrill-docs/docs/19-iam.md), [25](../../SecDrill-docs/docs/25-operations-deployment.md) "삭제와 abuse"·"백업과 복구".

## 요구 경로

1. 본인 재인증 후 요청(scope `ACCOUNT` 또는 `SESSION`), `confirmationToken` 검증
2. 접속 revoke, 활성 Lab 종료
3. 승인: 요청자와 다른 담당(SECURITY_ADMIN 또는 지정 담당)이 승인. 단독 자기 승인 금지(19)
4. 실행: 일반 앱 권한에 없는 전용 삭제 역할이 제한 `SECURITY DEFINER` 함수로 객체 bytes·identity 연결을 삭제하고, 만료된 Session 전체의 원장·head를 함께 제거(14)
5. tombstone 기록, projection 무효화, receipt(digest) 발급, 감사 기록
6. backup 복원 뒤 tombstone을 가장 먼저 재적용(25)

## 지금 있는 것

| 항목 | 상태 | 위치 |
|---|---|---|
| 요청·상태 계약 | 있음 | `deletion_requests`: scope·Session owner 일치(복합 FK)·결정 기록·완료 receipt CHECK |
| tombstone 계약 | 있음 | `deletion_tombstones`(USER·SESSION·ARTIFACT), 일반 앱 역할은 INSERT·SELECT만 |
| 일반 앱 권한의 append-only | 있음 | `control_app` 역할: evidence·audit_events·deletion_tombstones에 UPDATE/DELETE 없음, trigger 비활성화 불가. trigger도 별도로 거절 |
| 보관 기간 | 일부 | artifact `expires_at`을 sensitivity별로 기록(LEARNER·PUBLIC_SUMMARY 180일, RAW_LOG 30일). 만료 artifact는 learner에게 없음으로 응답. 실제 purge job 없음 |
| identity 최소 저장 | 있음 | `user_identities`는 issuer·subject만, email·profile 없음 |

## 없는 것(미구현)

- `POST /v1/deletion-requests`·`/v1/async-jobs/{id}` API, 재인증과 confirmationToken
- 전용 삭제 역할(`privacy_eraser` 제안)과 `SECURITY DEFINER` 함수: search_path 고정, 승인된 요청만 실행, 대상 Session 검증, receipt·audit
- 승인 워크플로와 2인 분리(운영자 디렉터리·break-glass는 T14)
- 객체 bytes 삭제, 만료 purge, orphan bytes sweeper, projection invalidate
- restore 후 tombstone 재적용 절차와 리허설(25)
- 원장 삭제 시 hash chain 처리: 만료 Session 전체 삭제는 chain을 함께 제거한다. 부분 삭제가 필요하면 payload를 artifact로 분리해 두었으므로 bytes만 지우고 row의 digest는 남긴다(14 "payload unavailable")

## 설계상 주의

- trigger를 끄는 방식의 삭제 금지(14). 함수는 테이블 소유자 권한으로 실행되므로 소유자와 런타임 로그인 역할을 분리해야 한다. 현재 로컬·테스트는 소유자로 접속하므로 **권한 분리는 테스트에서만 `SET ROLE control_app`으로 검증**됐다(배포 구성은 T14, D-14).
- `CREATE ROLE`은 migration 실행 역할에 CREATEROLE이 필요하다. 관리형 DB에서는 역할을 미리 만들어야 할 수 있다(D-14).
- 법적 보관 예외·처리 지역은 D-11에서 결정한다.

## 완료 조건(FR-10)

전용 역할·함수·승인 분리 구현, 실제 삭제 통합 테스트(요청→승인→실행→tombstone→receipt→감사), 일반 앱 역할로 삭제 함수 실행 불가 테스트, backup restore 리허설에서 tombstone 재적용 확인.
