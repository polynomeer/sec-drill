# 0003 Canonical digest와 Evidence hash chain

- 상태: Accepted (D-04, 소유자가 권장안 승인 2026-10-04)
- 날짜: 2026-10-04
- 담당: 프로젝트 소유자
- 원 초안: 없음(DESIGN_BASELINE F-05)
- 관련: T05, T09, FR-06, FR-07, [14 DB](../../SecDrill-docs/docs/14-database.md), [16 이벤트](../../SecDrill-docs/docs/16-events-async.md)

## 문제와 제약

15는 "canonical body digest"로 Idempotency-Key 재사용을 판정하고, 14는 Evidence에 hash chain을 요구하지만 계산식이 없었다. Web·Control Plane·Agent·검증 도구가 언어와 무관하게 같은 값을 계산해야 한다.

## 선택

- canonical 형식: RFC 8785(JCS). digest는 canonical UTF-8 bytes의 SHA-256 소문자 hex.
- 허용 값: 객체·배열·문자열·boolean·null·±(2^53−1) 정수. 정수가 아닌 수는 거절한다(제출이면 422). JCS의 부동소수 표기 규칙을 언어마다 맞추는 위험을 피한다.
- request digest: 요청 body 전체의 digest. 멤버 순서가 달라도 같은 요청이다.
- Evidence hash: `{eventType, occurredAt, payloadDigest, previousHash, seq, sessionId, source, trustLevel}`의 digest. `payloadDigest`는 `safe_payload`의 digest. 첫 `previousHash`는 `0`×64. 시각은 DB 정밀도인 microsecond로 자른 값을 쓴다.

## 비교한 대안

- 언어별 정렬 직렬화(Jackson ORDER_MAP_ENTRIES_BY_KEYS 등): 문자열 escape·key 정렬(UTF-16 vs code point)이 언어마다 달라진다.
- raw body bytes digest: 공백·순서 차이로 같은 요청이 409가 된다.

## 검증 증거

- 공통 벡터 [canonical.json](../../SecDrill-docs/contracts/fixtures/canonical.json): UTF-16 key 순서(U+1F600 < U+FB33), escape, 최대 정수, chain 1건. `scripts/check.py`(Python 독립 구현)와 `CanonicalJsonTest`(Kotlin)가 같은 값을 낸다.
- `SubmissionAcceptanceTest`: 멤버 순서가 다른 같은 body는 replay, 비정수 값은 422.
- 미검증: TypeScript 구현(T11), 저장된 chain 재검증 도구(T09).

## 결과와 되돌리는 조건

부동소수 값이 계약에 꼭 필요해지면 JCS 수치 규칙을 구현하고 벡터를 추가한 뒤 확장한다. 알고리즘을 바꾸면 기존 Ledger 검증을 위해 버전 필드를 추가한다.

## 영향을 받는 문서·계약·테스트

`shared/kernel` `CanonicalJson`·`Digests`·`EvidenceChain`, `LedgerAppender`, `IdempotencyStore`, 팩 14·16, `contracts/fixtures/canonical.json`
