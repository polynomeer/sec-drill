# 구현 상태

마지막 갱신: 2026-10-04

이 문서는 현재 상태만 유지한다. 지난 작업 기록은 Git 이력이 대신한다.

## 현재 단계

**T01 공통 계약과 최소 실행 골격**(프롬프트 02): 로컬 구현·검증 완료, 커밋 전. Phase 0(커밋 `cf87f61`, `95d7c67`)과 프롬프트 01 검토는 완료. 제품 기능·공격 Lab·채점은 시작하지 않았다.

## 활성 Task

| Task | 담당 | 대상 경로 | 시작일 | 메모 |
|---|---|---|---|---|
| T01 | Claude Code | `SecDrill-docs/contracts/`, 팩 13~16, `shared/`, `control-plane/`, Gradle, CI | 2026-10-04 | 구현 완료. 커밋과 GitHub CI 확인 후 종료. 세부는 [T01_PLAN](T01_PLAN.md#결과) |

## 완료 항목

| 항목 | 검증 근거 |
|---|---|
| 저장소 규칙·Claude Code 설정·hook | 실제 세션에서 `.env` 읽기(Read·Bash `cat`) 거절, 깨진 JSON에 hook 피드백 확인 |
| 문서·계약 검사 `scripts/check.py` | `--strict` 12종 PASS. 각 검사를 깨뜨리는 변형에서 FAIL 확인(초기 9종) |
| 단계별 프롬프트 23개 [prompts/](prompts/), 원본 묶음 `SecDrill-prompts/` | 원본과 `diff -r` 동일, 원본 MANIFEST 일치 |
| 계약 catalog `enums.json`(enum 18·오류 코드 17), fixture(이벤트 38·API 14) | `scripts/check.py` catalog·fixture 검사, `ContractCatalogTest` |
| 계약 보완 F-01~F-04·F-06·F-08, build_pack 수정(D-07) | 팩 재생성 후 manifest 일치. 처리 상태는 [DESIGN_BASELINE](DESIGN_BASELINE.md#처리-상태-2026-10-04) |
| Gradle 9.8.0·Kotlin 2.3.21·Spring Boot 4.1.1 골격, lockfile | wrapper 배포본·jar SHA-256이 공식 값과 일치. `./gradlew check` 통과 |
| 공통 커널(ID·시각·enum·오류 봉투) | kernel 테스트 9건, `ErrorEnvelopeTest` 6건 |
| Control DB 경계 검사 | 금지 의존(버전 있음·없음, Control Plane 모듈)을 주입하면 실패 |
| Flyway V1 + 실제 PostgreSQL 18.6 제약 테스트 | `CoreSchemaConstraintsTest` 13건 통과. F-01·F-03 제약 제거 시 해당 테스트 실패 |
| health·readiness·로컬 실행 | `HealthReadinessTest`(DB 중지 시 readiness 503). compose DB + `bootRun`에서 readiness 200, V1 적용 |

## 미검증 항목

- GitHub CI(`check`·`build` job) 실행: push하지 않음. Linux 호스트에서의 Testcontainers 동작 포함
- ask 규칙(commit·push 등)과 강제 push deny의 실제 동작: 외부 영향이 있어 시험하지 않음
- 동시성 경합(CAS·head lock), 개인정보 삭제 함수: T05·T09·T14 범위
- strong runtime 격리, 성능·카오스: 해당 구현 없음

## Blocker

없음. T05 착수 전 D-04(canonical digest) 결정이 필요하다.

## 다음 작업

1. T01 변경 검토(프롬프트 22)와 커밋(사용자 요청 시)
2. 프롬프트 03 = T02(인증과 소유권). D-09(OIDC provider)는 보류 상태라 로컬 fake OIDC로 진행
