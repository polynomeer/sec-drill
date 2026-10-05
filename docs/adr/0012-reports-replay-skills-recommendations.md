# 0012 리포트, Replay, 스킬 projection, 추천

- 상태: Proposed (T12 구현. 파일럿 calibration(10·23)과 projection cache 도입 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-007(Replay는 simulated reducer와 observed log 분리), ADR-013(skill은 versioned projection)
- 관련: T12, FR-07, FR-08, [10](../../SecDrill-docs/docs/10-evaluation-evidence.md), [13](../../SecDrill-docs/docs/13-state-machines.md), [22](../../SecDrill-docs/docs/22-replay.md), [23](../../SecDrill-docs/docs/23-adaptive-randomization.md)

## 문제와 제약

학습자는 점수가 어떤 근거에서 나왔는지, 도움이 얼마나 있었는지, 무엇이 평가 범위 밖인지, 왜 다음 사건을 추천받는지 따라갈 수 있어야 한다. 모델 재계산을 관측 사실처럼 보이면 안 되고, 플랫폼 오류·반복 풀이·상관된 증거로 역량을 과대 평가하면 안 된다. 재채점은 이전 결과를 지우지 않아야 한다. AI 설명은 이 단계에 넣지 않는다.

## 선택

- **정책 모듈** `:shared:learning`(순수 Kotlin): `skill-v1`·`taxonomy-v1` projection과 `recommend-v1` 추천. 10·23의 규칙을 정수(basis point)로 구현한다.
- **리포트**: finish가 REPORT job을 만들고 Control Plane worker가 활성 평가·원장·IR 모델에서 리포트를 만든다(SUBMITTED→EVALUATING→COMPLETED). revision은 `reports`에 고정되고, 활성 평가 집합이 바뀌면(재채점) 새 revision과 변경 이유를 쓴다. 차원마다 status·근거 종류·evidence anchor를 둔다. 도움 수준은 parent chain을 포함하며 H1~H2도 GUIDED로 보수적으로 표시한다(Report 계약에 LIGHT 단계가 없음).
- **재채점**: `POST /ops/v1/submissions/{id}/rejudge`(OPERATOR·SECURITY_ADMIN, 감사)가 새 GRADE job revision을 만든다. 평가는 새 revision이 활성이 되고 이전 revision은 남는다. 완료된 Session이면 REPORT job이 다시 생긴다.
- **Replay**: 원장 경로와 모델 경로를 분리한다. manifest는 100 seq chunk(digest), 누락 seq·만료/삭제 Artifact gap, IR checkpoint 목록을 준다. chunk 항목은 신뢰 수준과 Artifact 상태(원본 없음이면 요약·digest만)를 가진다. 액션 tick이 3의 배수일 때 checkpoint(전체 reducer 상태+digest)를 저장하고, seek는 digest가 맞는 가장 가까운 이전 checkpoint에서 다시 계산한다. 응답은 SIMULATED이고 그 tick에 기록된 digest를 함께 준다.
- **스킬**: 요청마다 활성 평가에서 계산한다(cache 없음). watermark는 활성 평가 id 집합의 digest다. 레벨과 confidence는 별도 필드이고 성공률은 확률이 아니라고 화면에 쓴다.
- **추천**: 리포트 생성 때 상위 3개와 이유 코드·네 항의 값을 계산하고 provenance(watermark·후보 수·선택·노출 이력)를 `recommendations`에 남긴다. 해설을 본 계열과 방금 끝낸 사건은 제외한다.
- **Web**: 리포트(차원 표·anchor 링크·revision 이동·추천 이유), Replay(seq 목록·검색·이전/다음·tick slider와 버튼·checkpoint·기록 digest 비교·만료 표시), 스킬(수준·신뢰도 분리) 화면.

## 비교한 대안

- 리포트를 GET 때 계산: 재채점 뒤 과거 리포트를 고정할 수 없다.
- projection을 테이블에 저장: 삭제·재채점·정책 변경 때 무효화가 필요하다. 표본 수가 작아 요청마다 계산해도 충분하고 결정적이다.
- IR 상태를 매 tick 저장: 재현을 증명하지 못한다. checkpoint + 재계산 + digest 비교가 22의 seek parity다.
- 추천에 AI 설명: 프롬프트가 제외했다. 이유는 규칙 항으로만 설명한다.

## 비용과 위험

- 스킬·추천 수치는 초기 휴리스틱이며 파일럿 calibration 전에는 학습자 판단 근거로 과신하면 안 된다.
- 지금 환경에서는 CTF·패치 결과가 demo라 projection에서 빠지므로 대부분 UNKNOWN이다. 실제 의미는 strong runtime 이후에 생긴다.
- 관측·조사 차원(timeline 답안)과 회고 의미 평가가 없다. 대응 점수 공식(contained일 때 정상 업무 성공률)은 단순 가정이다.
- 재채점에 dry-run·승인 단계가 없다(15의 rejudge 3단계 중 execute만).
- 리포트 worker 실패 시 같은 트랜잭션 안의 savepoint로 되돌리고 재시도하지만, 다중 인스턴스 경합은 job lease에만 의존한다.

## 검증 증거

- `SkillPolicyTest` 5건: 시스템 오류·데모·오답·회고 제외, UNKNOWN 기준과 confidence 분리, 같은 날 같은 계열·연결 Session 1회, 도움 배수·DEMONSTRATED의 독립 Transfer 2개 조건, 추천 이유·UNKNOWN=근거 부족·해설 계열 제외
- `CheckpointsTest` 2건: 모든 tick에서 처음부터 재생과 checkpoint seek의 digest 일치, 손상 checkpoint 무시·범위·engine 검사
- `InsightTest` 3건: 리포트 차원 7개와 근거 종류·Session 원장의 anchor·parent 도움 전파·판정 범위·추천 이유와 provenance·타인 404·finish 전 409; 재채점의 새 평가·리포트 revision과 이전 revision 보존·스킬 표본 1개 유지·UNKNOWN/LOW 분리·정책 버전 검사; Replay tick 0~4 seek digest가 기록과 일치·checkpoint 3·chunk digest·신뢰 수준·만료 Artifact gap과 표시·범위 422·타인 404
- Playwright 9건 추가(데스크톱·휴대폰): 점수→근거 anchor→Replay 항목, 추천 이유와 항 값, 모델/기록 구분과 만료 표시, slider·버튼 키보드 이동, UNKNOWN과 신뢰도 분리, 재채점 revision 이동, 휴대폰 리포트 가로 넘침 없음
- 미검증: 실제 사용자 데이터로의 calibration, 대량 원장에서의 Replay 성능, 스크린리더 실기기

## 결과와 되돌리는 조건

파일럿 결과로 가중치·임계값을 바꾸면 `skill-v2`·`recommend-v2`로 올리고 이전 리포트는 당시 정책으로 남긴다. projection 계산이 느려지면 watermark 기반 cache 테이블을 도입한다.

## 영향을 받는 문서·계약·테스트

`V10__reports_replay_recommendations.sql`, `contracts/schema.sql`·`openapi.yaml`(Report·ReplayManifest 확장, ReplayChunk·ReplayItem·ReplayState, SkillPage.successBps, report revision 파라미터)·`fixtures/api.json`, 팩 10·13·14·15·22·23, `:shared:learning`, `:execution:simulation`(Checkpoints), `:control-plane:insight`, `:control-plane:response`(Replay), `:control-plane:submission`(rejudge, REPORT 재생성), `:control-plane:lab`(finish→REPORT), `web/`, [T12](../development/T12.md)
