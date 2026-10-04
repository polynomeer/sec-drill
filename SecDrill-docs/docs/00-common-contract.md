# SecDrill 공통 계약

이 문서는 모든 설계에서 같은 단어와 같은 불변식을 사용하기 위한 기준이다. 아래 수치는 초기 운영 가정이며 실측 후 ADR로 변경한다.

## 용어

| 용어 | 정의 |
|---|---|
| Scenario | 사건 중심 콘텐츠의 논리적 식별자 |
| ScenarioVersion | 매니페스트·이미지·평가 정책을 고정한 불변 버전 |
| Drill | 특정 역량을 반복하는 훈련 과업 |
| Challenge | ScenarioVersion에 속하는 CTF·워게임 목표 |
| Session | 한 학습자가 한 버전·모드·seed로 수행하는 시도 |
| Lab | Session에 귀속된 일시적 실행 환경 |
| Submission | 플래그·패치·탐지 규칙·보고서의 불변 제출 |
| Execution | 특정 제출을 검증하는 한 실행; 재시도는 attempt 증가 |
| EvaluationRevision | 같은 제출의 평가 정책별 불변 판정 이력 |
| Evidence Ledger | 관측·행동·도움·판정 출처를 append-only로 기록하는 원장 |
| Projection | 원본 증거와 정책 버전에서 재계산 가능한 파생값 |
| Replay | 기록된 사건과 상태를 시간순으로 검토하는 기능 |
| Re-execution | 환경을 새로 만들어 실제 코드를 다시 실행하는 기능; Replay와 구별 |
| Transfer | 다른 사건 계열에서 힌트 없이 같은 역량을 검증하는 후속 Session |
| Mutant | 대표적인 불완전한 패치 또는 탐지 규칙; 콘텐츠 검증용 |
| Oracle | 채점기만 아는 정답 상태·숨은 테스트·공격 라벨 |
| Lease / fencing token | 작업 소유 기간 / 오래된 작업 결과를 거절하는 단조 증가 토큰 |

## 제품과 데이터 불변식

- 모드 enum: `CTF`, `WARGAME`, `PURPLE`, `PATCH`, `DETECTION`, `INVESTIGATE`. MVP는 앞의 세 모드만 독립 진입을 제공하고 나머지는 PURPLE의 단계다.
- 학습 단계 enum: `ANALYZE`, `ATTACK`, `OBSERVE`, `DETECT`, `CONTAIN`, `PATCH`, `VERIFY`, `POSTMORTEM`. Transfer는 같은 세션 단계가 아니라 연결된 새 Session이다.
- 사용자 점수 실패와 플랫폼 실패를 구분한다. 플랫폼 실패는 `SYSTEM_ERROR`, 역량 실패는 `FAIL`이다. SYSTEM_ERROR는 숙련도에 음의 증거로 반영하지 않는다.
- Session은 ScenarioVersion, rubricVersion, engineVersion, randomizationVersion, seed를 고정한다. 평가와 추천 정책 버전은 각각 별도로 기록한다.
- 공식 점수·플래그 정답·Oracle·관리자 토큰을 Lab에 전달하지 않는다. 브라우저와 Lab 로그는 비신뢰 입력이다.
- 원본 EvaluationRevision과 Ledger는 수정하지 않는다. 정정은 새 revision·supersedes 관계로 표현한다. 개인정보 삭제의 예외는 14·25 문서를 따른다.
- CTF 순위 점수와 역량 점수를 분리한다. 힌트 사용 성공을 독립 성공으로 표기하지 않는다. 재접속은 새 시도로 세지 않는다.

## 시간과 자원

ID는 UUID, 시각은 UTC RFC3339, UI는 사용자 시간대에 표시한다. 사건 순서는 서버가 Session별 부여하는 `seq`가 결정한다. 이벤트 발생 시각과 수집 시각을 각각 `occurredAt`, `ingestedAt`으로 저장한다. 시뮬레이션은 별도 `tick`을 사용한다.

| 항목 | MVP 가정 |
|---|---|
| 동시 학습자 / 실제 Lab | 50 / 최대 20 |
| 사용자 활성 Lab / 동시 채점 | 각 1개 / 각 1개 |
| 실제 Lab 기본 자원 | 2 vCPU, RAM 2 GiB, 임시 디스크 4 GiB, PID 256 |
| Lab idle / hard TTL | 15분 / 60분; 중지·만료 후 재개는 새 Lab |
| 제출 번들 / 일반 JSON 요청 | 압축 5 MiB, 해제 20 MiB·100파일 / 256 KiB |
| 실행 시간 | 컴파일 60초, 테스트 묶음 120초, 전체 채점 300초 |
| 실행 stdout / 사용자 로그 내보내기 | 1 MiB / Session당 10 MiB |
| 작업 heartbeat / lease | 10초 / 30초; 획득 후부터 계산 |
| 총 실행 attempt | 최대 3회; 최초 포함 |
| Evidence/평가 보관 / raw Lab 로그 | 기본 180일 / 30일 |
| 공식 목표 | API p95 300ms, Lab ready p95 60초, 채점 완료 p95 30초 |

목표는 27의 지정 하드웨어·워크로드에서 측정한다. 300초 timeout은 상한이고 30초는 표준 짧은 과제의 서비스 목표다. 타임아웃이 정상인 긴 과제는 별도 클래스와 UI 예상 시간을 갖는다.

## 계약 우선순위

MVP 계약은 `contracts/`에서 검증 가능한 형태로 관리한다. API가 입력을 정규화한 후 도메인이 상태 전이를 결정하고 DB 제약이 최종 불변식을 강제한다. UI가 상태·소유권·점수를 결정하지 않는다. 공식 라이브러리 버전은 구현 착수 시 지원 상태를 확인하고 lockfile과 이미지 digest로 고정한다.
