# 개인정보 삭제 경로 검토

상태: **실행 구현 완료(T14 ops, 프롬프트 16, [ADR 0013](../adr/0013-privacy-data-subject-execution.md)). FR-10 완료 조건을 통합 테스트로 충족한다.** 원장 row는 유지하고 bytes·identity만 삭제하는 모델이다(만료 세션 전체 원장 삭제는 조건부 trigger가 필요해 범위 밖). 검토일 2026-10-04, 구현일 2026-10-07. 근거: [14](../../SecDrill-docs/docs/14-database.md) "보관과 개인정보 삭제", [15](../../SecDrill-docs/docs/15-api.md) `POST /deletion-requests`, [19](../../SecDrill-docs/docs/19-iam.md), [25](../../SecDrill-docs/docs/25-operations-deployment.md) "삭제와 abuse"·"백업과 복구".

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

## 구현됨(프롬프트 16)

- `POST /v1/exports`·`POST /v1/deletion-requests`·`GET /v1/async-jobs/{id}`·`GET /v1/exports/{id}/download` API. `confirmationToken`은 살아있는 세션 보유 증명(CSRF 상수시간 비교)으로 검증 — 진짜 재인증은 D-09.
- 전용 역할 `privacy_eraser`와 `erase_deletion_request`/`reapply_tombstones` `SECURITY DEFINER` 함수(search_path 고정, APPROVED·2인 확인, 대상 scope 검증, receipt·tombstone). EXECUTE는 `privacy_eraser`에만, `control_app`은 불가. `user_identities` DELETE도 `control_app`에서 회수.
- 승인 워크플로와 2인 분리: 학습자 요청 + SECURITY_ADMIN 승인(`POST /ops/v1/deletion-requests/{id}/decision`).
- 객체 bytes 삭제, 만료 artifact purge, orphan bytes sweep, projection(reports·checkpoints·recommendations) 삭제, 접속 revoke·Lab 종료.
- restore 후 tombstone 재적용(`reapply_tombstones`)과 리허설 테스트(삭제→복원→재적용→부재).
- 원장: bytes만 지우고 PII 없는 row·digest는 남긴다(14 "payload unavailable"). hash chain 유지.

## 아직 없는 것

- 만료 Session 전체의 원장·head row 삭제: append-only trigger를 `privacy_eraser`에서만 허용하는 조건부 trigger가 선행돼야 한다(보안 경계 변경, 사용자 결정 대기).
- 진짜 본인 재인증 provider(D-09), 배포의 전용 `privacy_eraser` 접속 구성(D-14), S3 호환 store의 orphan 열거(D-15).
- 법적 보관 예외·처리 지역(D-11).

## 설계상 주의

- trigger를 끄는 방식의 삭제 금지(14). 함수는 테이블 소유자 권한으로 실행되므로 소유자와 런타임 로그인 역할을 분리해야 한다. 현재 로컬·테스트는 소유자로 접속하므로 **권한 분리는 테스트에서만 `SET ROLE control_app`으로 검증**됐다(배포 구성은 T14, D-14).
- `CREATE ROLE`은 migration 실행 역할에 CREATEROLE이 필요하다. 관리형 DB에서는 역할을 미리 만들어야 할 수 있다(D-14).
- 법적 보관 예외·처리 지역은 D-11에서 결정한다.

## 완료 조건(FR-10)

전용 역할·함수·승인 분리 구현, 실제 삭제 통합 테스트(요청→승인→실행→tombstone→receipt→감사), 일반 앱 역할로 삭제 함수 실행 불가 테스트, backup restore 리허설에서 tombstone 재적용 확인.
