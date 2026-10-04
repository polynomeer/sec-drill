# SecDrill Claude Code 실행 계획

이 계획은 문서 세트를 구현 저장소에 옮긴 뒤 사용하는 작업 지침이다. 실행 도구의 실제 버전별 기능을 가정하지 않고 일반적인 저장소 읽기·편집·테스트·리뷰 단위로 작성한다. 여기의 프롬프트는 아직 실행되지 않았다.

## 시작 지침

문서 00·02·03·11·13·15·16·17·20·30을 먼저 읽게 한다. `sources/` 같은 참조 디렉터리는 수정 금지로 선언한다. 제품명은 SecDrill, 공통 enum과 contract가 단일 기준이며 unsupported runtime일 때 strong isolation을 낮추지 못하도록 한다. 비밀·실제 공격 대상·관리 토큰은 테스트와 프롬프트에 넣지 않는다.

## 세션별 실행 순서

1. 계약 검토: 충돌·빠진 schema·불변식 목록을 작성하고 T01 validation과 migration을 구현.
2. 신원·소유권: T02를 만들고 모든 공개 endpoint에 cross-owner test 적용.
3. 비동기 기반: T05 Outbox/inbox/job lease를 구현하고 crash point tests로 확인.
4. 실행 격리: T04·T06의 local fake와 strong adapter를 분리하고 실제 host 검증 표시.
5. 한 사건: T03·T07·T09와 최소 UI를 연결해 CTF E2E.
6. Purple: T08·T10, postmortem과 최종 gates; T11 작업 공간.
7. 학습 결과: T12·T13 skill·Replay·전이·추천.
8. 운영 검증: T14 배포·측정·복구·삭제·파일럿 승인 자료.

## 작업 프롬프트 예제

```text
SecDrill T05의 첫 변경으로 submission 생성과 outbox 원자 저장을 구현한다.
00, 13, 14, 16, 30과 contracts를 먼저 읽고 관련 저장소 지침을 따른다.
기존 저장소 패턴을 확인한 뒤 최소 변경으로 구현한다.
동일 Idempotency-Key+동일 body는 같은 응답, 다른 body는409여야 한다.
커밋 이전 장애는 submission과 event 둘 다 없어야 하고,
커밋 이후 broker 장애는 outbox를 보존해야 한다.
실행 코드나 UI는 이번 변경에 추가하지 않는다.
계약·DB·장애 테스트를 수행하고 변경·검증·남은 위험을 보고한다.
```

```text
SecDrill T08의 Python patch grading을 구현한다.
학습 Lab와 grading VM은 분리하고 compile도 strong runtime 안에서 실행한다.
사용자 stdout과 result file을 공식 판정으로 신뢰하지 않는다.
참조 패치, 무조건 차단 패치, 한 endpoint만 고친 패치,
test framework를 바꾸는 패치를 fixture로 사용한다.
플랫폼 오류는 SYSTEM_ERROR이고 스킬에 음의 증거로 반영하지 않는다.
강한 runtime이 없으면 fake-only 개발 결과와 미검증 항목을 명시한다.
```

## 병렬 작업과 통합

신뢰 경계별로 사람이 정한 범위를 병렬 처리할 수 있다. Web은 공개 OpenAPI만, Content는 manifest/oracle format만, Agent는 protocol만 의존하도록 한다. 공통 enum·schema·migrations·job protocol을 여러 작업이 동시에 변경하지 않도록 소유자를 지정한다. 통합 전에 계약 검증과 도메인·DB tests를 수행하고 실제 runtime E2E는 별도 gate로 진행한다.

## 리뷰 요청

리뷰에는 상태 전이, stale callback, idempotency body mismatch, owner/Artifact 연결, 숨은 정답 노출, kill/cleanup, 개인정보 삭제, false skill precision을 우선 확인하게 한다. 수정은 실패 재현 fixture와 함께 한다. 실행 계획의 완료 표시는 실제 test report·artifact·commit reference를 붙여 관리하며 생성된 요약만으로 승인하지 않는다.
