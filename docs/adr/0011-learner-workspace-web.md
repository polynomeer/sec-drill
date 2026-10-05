# 0011 학습자 작업 공간 Web

- 상태: Proposed (T11 구현. 터미널 transport와 Report·Replay 화면(T12) 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-012(SSE 우선, terminal만 websocket) 일부
- 관련: T11, D-06, [05](../../SecDrill-docs/docs/05-learning-loop.md), [06](../../SecDrill-docs/docs/06-functional-spec.md), [07](../../SecDrill-docs/docs/07-ia-ux.md), [15](../../SecDrill-docs/docs/15-api.md)

## 문제와 제약

CTF·Wargame·Purple을 한 작업 공간에서 수행하되 점수·권한·단계 완료는 서버가 정해야 한다. Lab은 플랫폼과 다른 origin이어야 하고, 사용자 출력은 실행되면 안 되며, 키보드·작은 화면·재접속을 지원해야 한다. Web은 공개 OpenAPI만 써야 한다.

## 선택

- **스택(D-06)**: React 19 + Vite 8 + TypeScript 5.9, 정확한 버전 고정과 `package-lock.json`. router·상태 관리 라이브러리 없이 history API와 React state만 쓴다.
- **계약 결속**: `openapi-typescript`로 `src/api/schema.ts`를 만들어 커밋하고 `npm run check:api`가 계약과 다르면 실패한다. E2E의 가짜 API 응답도 같은 타입으로 검사된다.
- **배포**: Vite 빌드(`web/dist`)를 Control Plane이 `/app/`에서 제공한다(같은 origin이라 platform cookie·CSRF가 그대로 동작). 깊은 경로는 `index.html`로 forward한다. CSP `default-src 'self'`를 지키도록 inline script·style을 쓰지 않는다.
- **안전한 출력**: 모든 사용자·Lab 유래 텍스트는 React 텍스트 노드로만 그리고, ANSI·제어·bidi 문자를 제거하고 길이를 자른다. HTML 삽입 API를 쓰지 않는다.
- **separate origin**: Lab은 Gateway origin의 `connectUrl`을 `noopener,noreferrer` 새 창으로 연다. Gateway cookie가 SameSite=Strict라 iframe으로는 동작하지 않고, iframe을 쓰지 않는 것이 플랫폼 페이지와 Lab을 섞지 않는 방법이기도 하다.
- **재접속**: SSE를 fetch로 읽어 `Last-Event-ID`를 직접 보낸다. 마지막 seq는 Session별 sessionStorage에 두어 새로고침·재연결 뒤 놓친 항목부터 받는다. 연결 상태를 화면에 보인다.
- **상태 알림**: 하나의 polite live region에 Lab 상태 전이와 채점 결과만 알린다(폴링·스트림 항목은 읽지 않음).
- **오류 구분**: 5xx·네트워크·503은 "플랫폼 문제, 학습자 실패 아님, 진행물 보존", 4xx는 무엇을 고칠지(필드 오류·남은 gate). 평가 SYSTEM_ERROR도 학습자 실패가 아님을 밝힌다.
- **서버가 결정**: 완료 조건 목록은 `ScenarioDetail.completionRequirements`로 보여주기만 하고 finish 결과(409 `missingGates`)를 그대로 표시한다. 점수·gate·demo는 Evaluation 응답 그대로다. CTF→Purple은 `parentSessionId`를 가진 새 Session이다.
- **백엔드 보완**: 힌트 API(V9 `hint_grants`, oracle `hints`), SSE stream, Purple finish gate 5종, `ScenarioDetail`의 `patchPaths·actions·completionRequirements`, `Session.scenarioId`.

## 비교한 대안

- EventSource: 새 연결에 `Last-Event-ID`를 지정할 수 없어 새로고침 뒤 cursor 복구가 안 된다.
- Lab iframe: Strict cookie로 동작하지 않고 클릭재킹·혼동 위험이 있다.
- 가설·메모를 서버에 저장: 새 API·보존 정책·삭제 대상이 늘어난다. 이번에는 브라우저 저장임을 명시했다.
- 실제 백엔드로 E2E: Lab·출판 게이트·runner 진입점이 없어 로컬에서 전체 흐름을 띄울 수 없다. UI 동작은 계약 타입의 가짜 API로, 서버 동작은 Kotlin 통합 테스트로 나눠 검증했다.

## 비용과 위험

- E2E는 가짜 API다. 실제 서버와의 계약 어긋남은 타입·`check:api`·Kotlin 테스트로만 막는다. 실제 서버 smoke는 로그인·카탈로그·깊은 경로·CSP까지다.
- 터미널 탭은 비어 있다(transport 없음). 코드 탭은 textarea이며 diff·구문 강조가 없다.
- 가설·메모는 브라우저에만 있어 다른 기기에서 보이지 않는다.
- 접근성 자동 검사(axe 등)는 없다. 키보드·live region·작은 화면만 E2E로 확인했다.
- SSE는 요청마다 virtual thread가 1초 polling한다. 동시 접속이 많아지면 push 방식으로 바꿔야 한다.

## 검증 증거

- Playwright 16건(데스크톱·Pixel 7): 키보드만으로 카탈로그→사건→시작과 탭 이동(화살표·End·순환), 작은 화면 단일 열·가로 스크롤 없음·요약 고정, Lab 출력의 HTML 비실행·ANSI 제거·신뢰 수준 표시, Lab을 다른 origin 새 창(noopener)으로 열고 iframe 없음·데모 표시, SSE 재연결·새로고침 뒤 `Last-Event-ID` 복구와 중복 없음, 503/422/FAIL 안내 구분, SYSTEM_ERROR 안내·demo 유지, finish의 서버 missingGates 표시와 Purple 새 연결 Session, Lab 상태 전이 1회 알림
- `WorkspaceApiTest` 3건: 힌트 순서·누적 감점·재조회 무감점·원장에 본문 없음, SSE `Last-Event-ID` 재개·잘못된 cursor 400·타인 404, Purple finish의 서버 gate 목록·액션 반영·연결 Session과 원본 불변·타인 부모 404
- 실제 Control Plane(브라우저): `/app/` 로드와 CSP 위반 없음, 개발 로그인, 카탈로그 호출, 깊은 경로 forward
- 미검증: 실제 Lab·채점까지의 브라우저 전체 흐름, 스크린리더 실기기, 터미널

## 결과와 되돌리는 조건

터미널 transport(websocket)가 생기면 터미널 탭을 연결한다. 서버 측 노트 API가 생기면 브라우저 저장을 옮긴다.

## 영향을 받는 문서·계약·테스트

`web/`, `V9__hint_grants.sql`, `contracts/schema.sql`·`openapi.yaml`(ScenarioDetail·Session 필드)·`private-oracle.schema.json`(hints)·`fixtures/api.json`, 팩 07·15, `:control-plane:lab`(힌트·finish gate·scenarioId), `:control-plane:evidence`(SSE), `:control-plane:catalog`, `:content:format`(hints 검증), CI `web` job, [T11](../development/T11.md)
