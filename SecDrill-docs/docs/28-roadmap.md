# SecDrill 로드맵

로드맵은 달력 확약이 아니라 의존성과 종료 조건이다. 2명의 개발자와 파트타임 콘텐츠 검수자를 가정한 12주 MVP 예시이며 runtime 검증·콘텐츠 비용에 따라 재산정한다.

| 단계 | 기간 가정 | 산출물 | 종료 조건 |
|---|---|---|---|
| 0 계약·격리 spike | 1~2주 | schema/API, strong runtime, network policy | 타인 Lab·egress 차단과 TTL 자율 회수 |
| 1 수직 기능 | 3~4주 | 한 사건 CTF, 로그인, 제출, job, Ledger | 한 실제 목표와 독립 판정·중복 결과 차단 |
| 2 Purple 완결 | 5~7주 | detection·IR·patch·회귀·report·Replay | 재현부터 수정 증명까지 E2E |
| 3 전이·콘텐츠 | 8~9주 | 6버전, skill·추천·seed 검증 | 모든 reference·mutant·Transfer 통과 |
| 4 파일럿 준비 | 10~12주 | IAM review·배포·부하·삭제·restore·10명 파일럿 | PRD 출시 게이트와 학습 결과 검토 |
| P1 전문 학습 | MVP 후 | 독립 Patch/Detection/Investigate, 가상 IAM, counterfactual | 반복 사용과 학습효과 개선 |
| P2 조직·대회 | P1 지표 후 | 비공개 과제, team GameDay, jeopardy contest, 공유 리포트 | 조직 권한·동의·공정성·원가 검증 |

## Critical path

runtime 격리 → job/lease/fencing → 독립 verifier → 콘텐츠 validation → 패치 gate → Ledger/report → Transfer calibration이 주요 경로다. Web 편의 화면과 optional AI는 병렬 개발할 수 있지만 공식 채점 신뢰성이 확인되기 전 기능 확대를 위해 critical path를 우회하지 않는다.

## 일정 위험

강한 runtime의 host·kernel 요구, 정답과 mutant 품질, 탐지 ground truth 정의, 실제 Lab 관측 수집, 과제별 실행 시간 차이가 큰 위험이다. 2주 spike 이후 실제 추정치로 일정·capacity를 바꾼다. 콘텐츠 저작도 기능 구현과 같은 backlog·검수 시간을 배정한다.

## Gate 기반 중단·확장

파일럿에서 Transfer 효과가 없으면 추천과 콘텐츠·근거 피드백을 개선한다. Lab 원가가 높으면 세션 TTL·shared immutable image cache·simulated investigation을 조정한다. 공격 Lab를 학습자가 원치 않는 경우 모드를 제거하기보다 개인별 진입 경험을 검증한다. SSO·결제·모바일·실제 cloud는 학습 신뢰성·반복 사용·운영 담당이 확보된 뒤 추진한다.
