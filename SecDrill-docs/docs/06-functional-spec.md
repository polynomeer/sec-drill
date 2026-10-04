# SecDrill 기능 명세

기능별 입력·서버 동작·결과와 실패 경험을 정의한다. 공통 인증·오류·멱등성은 [15](15-api.md)를 따른다.

| 기능 | 입력 | 처리와 출력 | 예외 |
|---|---|---|---|
| 카탈로그 | 모드·역량·난이도·cursor | 공개 버전·예상시간·선수 지식 반환 | 숨은 목표·라벨·seed 비공개 |
| Session 시작 | versionId, mode, clientRequestId | 버전·정책 고정, seed 생성, CREATED 반환 | 모드 미지원 422, 동일 키 다른 입력 409 |
| Lab 요청 | Session ID, expectedVersion | 한 사용자 1개 quota, provisioning 작업 생성 | quota 초과 429, queued 이유 반환 |
| 접속 | Session ID | 인증 proxy가 Lab ID·소유자·상태 확인 | TTL 만료 즉시 접속 차단 |
| 힌트 | challengeId, level | 정해진 다음 힌트 지급·Ledger 기록 | 이전 힌트 재조회는 중복 감점 없음 |
| 플래그 | challengeId, flag | 서버 비밀 기반 검증, 최초 성공만 증거 생성 | 오답은 일반 verdict; raw flag 미보관 |
| 목표 증명 | 요청 seq·합성 리소스 ID | 독립 관측기 이벤트와 연결 | 사용자가 쓴 성공 JSON은 인정하지 않음 |
| 패치 | 허용 경로의 파일 map, explanation | canonical bundle 저장, digest, 실행 예약 | traversal·symlink·허용 외 경로 422 |
| 탐지 규칙 | 제한 DSL JSON | AST 검증, timeout 있는 replay 평가 | 임의 SQL·shell·외부 함수 금지 |
| 대응 | actionType, parameters, expectedVersion | 단일 Session sequencer가 model 상태 전이 | 중복 키 같은 결과, 오래된 version 409 |
| 종료 제출 | postmortem, evidenceRefs | 모드 필수 목표 확인, EVALUATING 진입 | 미충족 단계 409와 누락 목록 |
| 리포트 | Session ID | 판정·근거·도움·다음 추천 | 평가 지연은 진행 상태; 실패를 점수 0으로 대체하지 않음 |
| 내보내기 | 본인 요청 | 비밀 제외한 기록·산출물 ZIP job | 파일 생성 실패 재시도; 만료 URL 표시 |

## 초안과 공식 제출

에디터 초안은 브라우저 저장으로 시작하고 공식 제출 시 파일 묶음을 서버에 저장한다. 자동 저장 실패가 공식 제출 완료 표시를 만들면 안 된다. 제출 버튼은 서버가 Submission ID를 반환한 후에만 성공을 알린다. 사용자의 작업물을 임의 Git URL에서 가져오거나 dependency 설치를 위해 외부 네트워크를 열지 않는다.

## 결과 규칙

공식 Evaluation verdict는 `PASS`, `FAIL`, `SYSTEM_ERROR`다. 패치 하위 gate는 `VERIFIED`, `NOT_VERIFIED`, `INCONCLUSIVE`다. 채점 세부 로그에서 숨은 데이터·정답·플래그가 포함될 수 있는 항목은 정형 요약으로만 노출한다. 학습용 재현 상세는 사용자가 이미 접근 가능한 합성 리소스로 제한한다.

## 운영 기능

콘텐츠 등록·승인·출판·차단은 처음에는 운영 CLI와 내부 API로 수행한다. 공개 API는 읽기 전용 콘텐츠 탐색만 제공한다. 재채점은 dry-run → 변경 영향 확인 → 승인 → 새 revision 생성 순서다. 승인자가 원래 요청자와 달라야 하는 공식 출판·전체 재채점 작업은 개인 개발 환경의 편의 모드와 분리한다.
