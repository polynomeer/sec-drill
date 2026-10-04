# 0001 Control Plane 개발 스택과 migration

- 상태: Proposed (소유자가 개발용 선택으로 결정, 외부 파일럿 전 재검토)
- 날짜: 2026-10-04
- 담당: 프로젝트 소유자
- 원 초안: ADR-001(modular monolith)의 일부, `11` 기술 제안
- 관련: T01, [DECISIONS_REQUIRED](../development/DECISIONS_REQUIRED.md) D-02·D-05, [11 아키텍처](../../SecDrill-docs/docs/11-architecture.md), [14 DB](../../SecDrill-docs/docs/14-database.md)

## 문제와 제약

T01(프롬프트 02)은 "확정된 스택"으로 Control Plane 골격과 migration을 요구한다. 설계 팩은 Kotlin/Spring을 제안만 했다. 실행 영역(`execution/*`)은 Control DB에 접근하지 못해야 하고(11), 런타임 자동 DDL은 금지다(14).

## 선택

- Kotlin 2.3.21, Spring Boot 4.1.1, JDK 21 LTS toolchain, Gradle 9.8.0 wrapper(배포본 SHA-256 고정). 라이브러리 버전은 Spring Boot BOM이 관리하고 `gradle.lockfile`로 고정한다.
- 모듈: `:shared:kernel`(ID·시각·enum·오류 봉투, Spring·DB 의존 없음), `:control-plane:app`(조립·HTTP 오류·health·migration). 나머지 `30`의 모듈은 내용이 생길 때 추가한다.
- 경계: `:execution:*`와 `:shared:*`의 compile·runtime classpath에 JDBC·driver·pool·migration·ORM 라이브러리나 `:control-plane:*` 모듈이 있으면 `checkModuleBoundary`가 `check`를 실패시킨다.
- migration: Flyway 순수 SQL(`db/migration/V<n>__*.sql`). 앱 시작 시 적용은 기본 비활성(`SECDRILL_DB_MIGRATE_ON_START`)이며 로컬 개발에서만 켠다.
- DB 검증: Testcontainers로 `postgres:18.6-alpine`을 index digest로 고정해 실행한다.

## 비교한 대안

- Java + Spring: 같은 생태계, 코드량과 null 안정성에서 Kotlin을 택함.
- Liquibase·자체 SQL runner: Flyway가 순수 SQL을 유지하면서 checksum 검증을 제공해 가장 단순.
- docker compose로 띄운 DB에 테스트 연결: 테스트가 DB 수명을 관리하지 않아 로컬·CI 결과가 갈릴 수 있음. compose는 로컬 개발 DB 용도로만 둔다.

## 비용과 위험

- JVM 기반이라 Agent처럼 작은 실행 바이너리가 필요한 영역에는 별도 판단이 필요하다(T06).
- Testcontainers는 Docker가 필요하다. Docker 없는 환경에서 DB 테스트는 실패하며 skip으로 숨기지 않는다.
- 플러그인 classpath(buildscript)는 lockfile에 포함하지 않았다. 플러그인 버전은 `libs.versions.toml`에 고정한다.

## 검증 증거

- `./gradlew check`: kernel 9개, app 20개 테스트 통과(2026-10-04, macOS, Docker 28.0.4, PostgreSQL 18.6)
- 경계 검사에 금지 의존(버전 있음·없음, Control Plane 모듈)을 주입하면 실패함을 확인
- V1에서 F-01·F-03 제약을 제거하면 해당 DB 테스트 2개가 실패함을 확인
- 로컬 `bootRun` + compose DB에서 readiness 200, migration V1 적용 확인
- 미검증: GitHub CI 실행, Linux 호스트, 성능

## 결과와 되돌리는 조건

외부 파일럿 전에 운영 배포 방식(D-10)과 함께 재검토한다. Agent·Orchestrator 구현(T05·T06)에서 JVM이 격리·자원 요구를 만족하지 못하면 해당 영역만 다른 스택으로 분리한다.

## 영향을 받는 문서·계약·테스트

`build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `control-plane/app/src/main/resources/db/migration/`, `CoreSchemaConstraintsTest`, `HealthReadinessTest`, `scripts/check.py`(V1↔schema.sql 일치), [VERIFICATION](../development/VERIFICATION.md)
