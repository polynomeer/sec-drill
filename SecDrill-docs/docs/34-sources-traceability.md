# SecDrill 출처와 요구사항 추적성

참조 대화 전체를 확인하고 공개 저장소의 아래 문서를 2026-10-04에 읽었다. SecDrill의 요구사항은 사용자의 요청이고 구체적인 수치·MVP 범위·스택·정책은 이번 산출물의 제안이다.

## 확인한 출처

| 출처 | 확인한 사실 | 적용 범위와 한계 |
|---|---|---|
| [CodeDrill 제품 맥락](https://github.com/polynomeer/code-drill/blob/main/docs/project-context.md) | 실행 근거·코칭·전이, append-only Evidence, Level/Confidence 분리, 분리 실행 경계, Outbox·fencing | 문서상 설계·맥락을 확인; 모든 실제 코드·원본 DOCX를 감사하지 않음 |
| [CodeDrill 기능 로드맵](https://github.com/polynomeer/code-drill/blob/main/docs/feature-roadmap.md) | mutant 검증·역량 projection·프로젝트 평가 확장 | 필요한 제품 철학 참고; 규모·구현 완료 수치 인용하지 않음 |
| [SysDrill README](https://github.com/polynomeer/sys-drill/blob/main/README.md) | Build·Design·조건 변화·Wargame·대응·회고, Bridge, 두 층 시뮬레이션 | 개념 확장 참고; 구현 재사용 보장하지 않음 |
| [SysDrill 아키텍처](https://github.com/polynomeer/sys-drill/blob/main/docs/ARCHITECTURE.md) | modular monolith, rule+AI, 버전 고정, 실제 관측 snapshot과 rule replay 구별 | 출력 길이 제한 범위의 관련 절 확인; 전체 코드 검증 아님 |
| 참조 대화 SecDrill | Purple Range, Breach Replay, Secure Code, Detection, IAM, GameDay, CTF 포함 요구 | 아이디어 출처; 기존 플랫폼 구현의 사실 근거로 쓰지 않음 |

CodeDrill은 root README 경로가 404여서 실제 docs/project-context.md와 feature-roadmap.md를 사용했다. 원본 DOCX 우선이라는 저장소 안내가 있으므로 이 산출물은 위 Markdown에서 확인한 철학을 참고한 독립 설계다. 출처에 없는 SecDrill의 배포 성능·학습 효과·안전성을 검증된 사실로 주장하지 않는다.

## 요구와 설계 연결

| 요구 | 핵심 문서 | 구현·검증 |
|---|---|---|
| 상위 학습 경험과 CTF/Wargame | 01,03,05,09 | T07,T11,T13; FR-04/05 |
| 실행 증거·스킬·전이 | 10,22,23 | T09,T12,T13; FR-07/08 |
| 구현 가능한 API/DB/상태 | 12~16,contracts | T01,T05; contract/DB tests |
| 공격 실행의 격리·권한 | 17~20 | T02,T06,T08; NFR-01/02 |
| 탐지와 사고 대응 | 21 | T10; DSL·ground truth·reducer tests |
| 운영·성능·장애·배포 | 24~27 | T14; chaos/restore/deletion |
| 착수·에이전트 실행·소개 | 28~33 | task와 검증 자료 연결 |

## 구현 전 결정할 항목

OIDC provider, 실제 호스팅과 strong runtime, alert 수신자, 개인정보 처리 지역·보관 예외, 제품·콘텐츠 라이선스, 운영자 2인 승인 인력, 최초 지원 patch 이미지, 실제 비용 한도를 결정한다. 이는 문서 작성의 누락이 아니라 외부 계정·예산·운영 주체 선택이 필요한 항목이다. 내부 수직 구현은 fake와 합성 데이터로 진행할 수 있지만 외부 공격 Lab 개방은 해당 결정과 검증 뒤에 수행한다.
