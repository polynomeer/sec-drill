# 0013 개인정보 데이터주체 실행(export·삭제·tombstone 재적용)

- 상태: Proposed (T14 ops 구현. 실제 재인증 provider(D-09)와 배포 역할 분리(D-14) 전 재검토)
- 날짜: 2026-10-07
- 담당: 프로젝트 소유자
- 관련: 프롬프트 16, FR-10, NFR-03, [14](../../SecDrill-docs/docs/14-database.md), [19](../../SecDrill-docs/docs/19-iam.md), [25](../../SecDrill-docs/docs/25-operations-deployment.md), [PRIVACY_ERASURE_REVIEW](../development/PRIVACY_ERASURE_REVIEW.md)

## 문제와 제약

[검토](../development/PRIVACY_ERASURE_REVIEW.md)에서 요청·tombstone 계약과 append-only 권한만 있고 실제 export·삭제 실행이 없어 FR-10 게이트를 통과하지 못했다. 원장(evidence)은 append-only trigger로 보호되고 그 safe_payload는 이미 PII가 제거돼 있다. 삭제가 hash chain을 깨면 안 되고, 일반 앱 권한이 단독으로 계정을 비식별화할 수 있으면 안 되며, backup 복원 뒤에도 삭제된 주체는 삭제 상태로 남아야 한다.

## 선택

- **원장 row는 남긴다**: 삭제는 artifact **bytes**와 artifact row, 리포트·checkpoint·추천 projection, ACCOUNT 범위에서 `user_identities` 연결을 제거한다. PII 없는 원장 row와 hash chain은 유지한다(14 "payload unavailable"). append-only trigger를 끄거나 우회하지 않는다. 만료 세션 전체의 원장 삭제(조건부 trigger 필요)는 범위 밖으로 남긴다.
- **전용 역할 `privacy_eraser`**: `erase_deletion_request`·`reapply_tombstones`를 `SECURITY DEFINER`·`search_path=public` 함수로 두고 EXECUTE를 `privacy_eraser`에만 부여한다. `control_app`은 두 함수 EXECUTE가 없고 `user_identities` DELETE도 회수돼, 앱이 스스로 비식별화할 수 없다. 함수는 요청이 APPROVED이고 승인자가 요청자와 다른지(2인, 19) 확인한 뒤에만 실행하고, 삭제 대상마다 tombstone을 쓰고 요청을 receipt digest와 함께 COMPLETED로 바꾼다. 함수는 purge할 object key를 반환하고, 호출자가 store에서 bytes를 지운다(사이 크래시는 orphan sweeper가 회수).
- **승인 분리**: 학습자가 `POST /v1/deletion-requests`로 요청(재인증 증거 `confirmationToken`), SECURITY_ADMIN 운영자가 `POST /ops/v1/deletion-requests/{id}/decision`으로 승인/거절. 실행은 scheduled 워커가 APPROVED 요청을 집어 접속 revoke→Lab 종료→함수 실행→bytes purge→감사 순으로 수행한다.
- **export**: `POST /v1/exports`가 소유자의 세션·제출·원장 safe_payload·평가를 JSON으로 모아 private object(`exports/<id>`)로 저장하고, `GET /v1/async-jobs/{id}`로 상태를 폴링, `GET /v1/exports/{id}/download`로 소유자 확인 후 digest 검증해 내려준다. oracle·정답·hidden test는 포함하지 않는다.
- **retention sweep**: scheduled 워커가 만료된 artifact의 bytes를 지우고 row를 soft-delete(`deleted_at`)하며, 참조 없는 store object를 grace 후 회수한다.
- **tombstone 재적용**: `reapply_tombstones`가 복원 뒤 되살아난 주체를 다시 지우고 purge할 key를 반환한다. 리허설 테스트가 삭제→복원(재삽입)→재적용→부재를 확인한다.

## 비교한 대안

- trigger를 조건부로 바꿔 만료 세션 원장까지 삭제: 더 완전하지만 append-only 보안 경계를 바꾸는 큰 변경이라 보류(사용자 결정).
- 앱이 직접 삭제: 전용 역할의 2인·감사·search_path 고정 보호가 사라진다.
- 삭제를 승인 트랜잭션에서 동기 실행: 접속 revoke·Lab 종료·bytes purge가 묶여 실패 경로가 복잡하다. scheduled 워커가 재시도에 안전하다.
- export를 session-scoped artifacts 테이블에 저장: ACCOUNT export는 세션이 없어 맞지 않는다. `exports/<id>` object + `export_jobs` row로 분리한다.

## 비용과 위험

- 실제 재인증 provider가 없어(D-09) `confirmationToken`은 현재 **살아있는 세션 보유 증명**(CSRF 토큰 상수시간 비교)으로만 검증한다. 진짜 재인증은 D-09에서.
- 로컬·테스트는 소유자 역할로 접속하므로 앱이 함수를 바로 실행한다. 권한 분리(`control_app`은 실행·비식별화 불가)는 `SET ROLE control_app` 테스트로만 검증했고, 배포에서 전용 `privacy_eraser` 접속을 쓰는 구성은 D-14다.
- 원장 row를 남기므로 만료 세션의 메타데이터(seq·type·digest)는 보관 기간 동안 남는다. 완전 원장 삭제가 필요하면 조건부 trigger 변경이 선행돼야 한다.
- orphan bytes 회수는 로컬 store 열거에 의존한다. S3 호환 store(D-15)에서는 해당 store의 열거·grace로 다시 검증해야 한다.

## 검증 증거

- `PrivacyExecutionTest`: 요청→승인(2인)→실행→tombstone→receipt→감사 전 과정, 자기 승인·미승인 실행 거절, export 조립·소유자 다운로드·oracle 부재, retention/orphan sweep, 삭제→복원→tombstone 재적용→부재.
- `PrivacyPrivilegeTest`: `SET ROLE control_app`로 `erase_deletion_request`·`reapply_tombstones` 실행과 `user_identities` DELETE가 모두 42501로 거절됨.
