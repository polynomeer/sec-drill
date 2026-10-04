# SecDrill 작업 단위별 추가 프롬프트 예제

이 파일은 개발 문서 `31-claude-code-plan.md`에 포함된 구체적인 작업 요청을 함께 추출한 참고 자료다. 아래 두 프롬프트는 각각 별도로 실행한다. 전체 T05/T08 프롬프트를 이미 완료했다면 같은 작업을 다시 수행하지 않는다.

## Outbox 원자 저장

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

## Python 패치 채점

```text
SecDrill T08의 Python patch grading을 구현한다.
학습 Lab와 grading VM은 분리하고 compile도 strong runtime 안에서 실행한다.
사용자 stdout과 result file을 공식 판정으로 신뢰하지 않는다.
참조 패치, 무조건 차단 패치, 한 endpoint만 고친 패치,
test framework를 바꾸는 패치를 fixture로 사용한다.
플랫폼 오류는 SYSTEM_ERROR이고 스킬에 음의 증거로 반영하지 않는다.
강한 runtime이 없으면 fake-only 개발 결과와 미검증 항목을 명시한다.
```
