# SecDrill 개발 문서 세트

SecDrill은 격리된 환경에서 취약점을 찾고, 공격을 재현하고, 탐지·대응·수정한 뒤 변형 공격과 새로운 과제로 실력을 검증하는 보안 실전 훈련 플랫폼이다. CTF와 취약점 탐색 워게임을 독립적으로 즐길 수 있으며 동일한 사건을 Purple 훈련으로 이어갈 수 있다.

작성일: 2026-10-04. 문서 버전: 0.1. 상태: 개발 착수용 제안 초안. 구현 완료나 실측 성능을 의미하지 않는다. 사용자 요구와 참조 제품의 철학을 바탕으로 구체화한 설계이며, 일정·수치·스택 선택은 검증할 가정이다.

## 읽는 순서

제품 결정은 00 → 01 → 02 → 03, 개발 착수는 11 → 12 → 13 → 14 → 15 → 16 → 20 → 30 순으로 읽는다. 문서 간 충돌 시 공통 계약 00, MVP 범위 03, 구조화 계약 contracts 순으로 우선한다. 계약 변경은 관련 문서와 수용 기준을 함께 개정한다.

| 문서 | 목적 |
|---|---|
| [00 공통 계약](docs/00-common-contract.md) | 용어, ID, 상태, 수치, 버전 기준 |
| [01 제품 기획서](docs/01-product-plan.md) | 문제, 차별성, 제품 가설 |
| [02 PRD](docs/02-prd.md) | 요구사항과 수용 기준 |
| [03 MVP 범위](docs/03-mvp-scope.md) | 출시 포함·제외와 게이트 |
| [04 사용자와 JTBD](docs/04-personas-jtbd.md) | 사용자 과업과 검증 인터뷰 |
| [05 핵심 학습 루프](docs/05-learning-loop.md) | 모드 연결과 전이 |
| [06 기능 명세](docs/06-functional-spec.md) | 입력, 처리, 출력, 오류 |
| [07 정보구조와 UX](docs/07-ia-ux.md) | 화면, 작업 흐름, 접근성 |
| [08 콘텐츠 가이드](docs/08-content-guide.md) | 사건 패키지 저작과 품질 |
| [09 CTF와 워게임](docs/09-ctf-wargame-guide.md) | 플래그, 목표, 힌트, 공정성 |
| [10 평가와 증거](docs/10-evaluation-evidence.md) | 채점, 원장, 스킬 프로파일 |
| [11 시스템 아키텍처](docs/11-architecture.md) | 신뢰 경계와 배포 단위 |
| [12 도메인 모델](docs/12-domain-model.md) | Aggregate와 불변식 |
| [13 상태 머신](docs/13-state-machines.md) | 세션, 실행, 평가, 정리 |
| [14 DB 설계](docs/14-database.md) | 스키마, 제약, 마이그레이션 |
| [15 API 명세](docs/15-api.md) | 공개 API와 오류 규칙 |
| [16 이벤트와 비동기](docs/16-events-async.md) | Outbox, 임대, 멱등성 |
| [17 샌드박스와 격리](docs/17-sandbox-isolation.md) | 실행 경계와 자원 통제 |
| [18 보안 위협모델](docs/18-threat-model.md) | 공격면, 통제, 잔여 위험 |
| [19 권한과 IAM](docs/19-iam.md) | 사용자·운영자·워크로드 권한 |
| [20 실행과 채점 엔진](docs/20-execution-grading.md) | 판정 파이프라인과 실패 구분 |
| [21 탐지와 사고 대응](docs/21-detection-ir.md) | 탐지 평가와 대응 시뮬레이션 |
| [22 Replay](docs/22-replay.md) | 기록 재생과 결정론적 복원 |
| [23 랜덤화와 Adaptive Drill](docs/23-adaptive-randomization.md) | seed, 동치 변형, 추천 |
| [24 Observability](docs/24-observability.md) | 운영 지표와 학습 관측 분리 |
| [25 운영과 배포](docs/25-operations-deployment.md) | 출시, 백업, 장애 Runbook |
| [26 테스트 전략](docs/26-testing.md) | 검증 계층과 출시 게이트 |
| [27 성능과 카오스](docs/27-performance-chaos.md) | 부하 가정과 장애 실험 |
| [28 로드맵](docs/28-roadmap.md) | 단계, 의존성, 종료 조건 |
| [29 ADR 초안](docs/29-adr-index.md) | 결정 제안과 재검토 조건 |
| [30 구현 계획](docs/30-implementation-plan.md) | 작업 패키지와 수용 기준 |
| [31 Claude Code 실행 계획](docs/31-claude-code-plan.md) | 작업 순서, 프롬프트, 검증 |
| [32 README 초안](docs/32-product-readme-draft.md) | 구현 저장소용 소개 |
| [33 포트폴리오 초안](docs/33-portfolio-draft.md) | 문제·결정·증명 중심 설명 |
| [34 출처와 추적성](docs/34-sources-traceability.md) | 출처, 요구사항 연결, 미결정 |

## 함께 제공하는 계약

- `contracts/openapi.yaml`: MVP 공개 API의 OpenAPI 3.1 초안.
- `contracts/schema.sql`: 핵심 영속 모델의 PostgreSQL DDL 초안. 운영 단일 출처는 구현 저장소의 번호 있는 migration이다.
- `contracts/enums.json`: enum과 오류 코드 catalog. SQL·OpenAPI·이벤트 schema와 구현 코드가 이 값과 일치해야 한다.
- `contracts/fixtures/`: 이벤트 봉투와 API 본문의 합성 정상·비정상 예제. 계약 검사가 정상 통과·비정상 거절을 확인한다.
- `contracts/event.schema.json`: 제어 영역 이벤트 봉투 JSON Schema.
- `examples/scenario.json`: 테넌트 데이터 유출 시나리오의 공개 매니페스트.
- `examples/private-oracle.json`: 동일 사건의 채점기 전용 정의 예제. 학습자에게 제공하지 않는다.
- `examples/acceptance-matrix.csv`: 요구사항과 문서·테스트 연결.
- `ALL-IN-ONE.md`: 문서를 순서대로 합친 통합본. 생성본이므로 개별 문서를 수정한 후 재생성한다.
- `MANIFEST.sha256`: 다운로드 무결성 확인용 목록.
- `VALIDATION.json`: 문서 링크·OpenAPI·JSON Schema·SQL 구문 검증 결과와 미검증 범위.
- `tools/build_pack.py`: 문서 수정 후 통합본·무결성 목록·ZIP을 재생성하는 도구. 기본 검사는 Python 표준 라이브러리로 동작하며 추가 계약 검증은 선택적 검증 라이브러리를 사용한다.

모든 예제는 계약 초안이며 실행 서버나 취약한 앱 구현을 포함하지 않는다. SQL은 별도 임시 DB에 적용해 검증한 뒤 마이그레이션으로 편입한다. OpenAPI와 JSON Schema는 실제 구현의 CI 계약 검사에 연결해야 한다.

## 제품명 변경

표시 제품명은 정확히 `SecDrill`로 통일했다. 전체 텍스트에서 이 문자열을 한 번 전역 치환하면 된다. 디렉터리 이름과 ZIP 이름은 별도로 바꾼다. API 경로·DB 테이블·도메인 키에는 제품명을 넣지 않아 이름 변경이 계약 변경으로 이어지지 않는다.

## 문서 운영

요구 변경 → 요구사항 ID 확인 → 공통 계약과 ADR 개정 → API·DB·이벤트 영향 확인 → 테스트 수정 → 구현 순서로 진행한다. 파일은 Markdown UTF-8이며 저장소·에디터에서 바로 편집할 수 있다. 통합본은 읽기 편의를 위한 복제본이고 단일 출처는 `docs/`의 개별 문서다.
