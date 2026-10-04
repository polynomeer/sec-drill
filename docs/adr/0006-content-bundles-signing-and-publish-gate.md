# 0006 콘텐츠 번들, 서명, 출판 게이트

- 상태: Proposed (T03 구현, 서명 키 운영(D-16)과 실제 runtime verifier(T06·T08) 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-010(signed versioned content bundles)
- 관련: T03, FR-02, FR-09, [08](../../SecDrill-docs/docs/08-content-guide.md), [09](../../SecDrill-docs/docs/09-ctf-wargame-guide.md), [19](../../SecDrill-docs/docs/19-iam.md)

## 문제와 제약

공개 manifest와 grader 전용 oracle을 분리하고, 서명·검증된 번들만 작성자와 다른 승인자를 거쳐 출판해야 한다. 실제 runtime 검사(참조 해답·mutant·seed)는 아직 실행할 수 없으므로, 검증하지 않은 번들이 게이트를 통과하면 안 된다. 예제의 placeholder와 publishable=false는 바꾸지 않는다.

## 선택

- **형식**: `manifest.json`·`oracle.json`·`public/`·`private/`·`signature.json`. 구조 계약은 두 JSON Schema. digest는 RFC 8785 canonical(ADR 0003)로 archive 바이트와 무관하다. 계약 문서에는 부동소수를 두지 않는다(비율은 basis point).
- **서명**: JDK 내장 Ed25519, domain-separated message `secdrill-bundle-v1\n<bundleDigest>`. 개인키는 작성자·CI 파일(0600)에만 있고 Control Plane은 `secdrill.content.trusted-keys`의 공개키만 가진다. 이미지 자체의 registry 서명(cosign 등)은 runner(T06)에서 검증해야 하며 지금은 manifest 서명이 imageDigest를 덮는 데까지만 보장한다.
- **검증**: 정적 검사(구조, 버전 일치, rubric 합, 공개·비공개 분리와 oracle 값 누출 탐지, 실제 image digest, placeholder 없는 oracle 참조, publishable, 서명, 저장소 무결성)와 `RuntimeVerifier`. 기본 verifier는 모든 runtime 검사를 NOT_RUN으로 보고하고, `accepted-verifiers`(기본 `strong-runtime`, 아직 없음)에 없는 verifier의 결과는 PASS가 될 수 없다. 결과는 PASS·FAIL·INCOMPLETE이며 PASS만 VALIDATED로 간다.
- **역할**: operator token role에 AUTHOR·REVIEWER를 추가했다. AUTHOR가 등록·검증, 작성자가 아닌 REVIEWER가 승인, OPERATOR·SECURITY_ADMIN·REVIEWER가 차단한다.
- **DB 강제**: 버전 내용 불변, 상태는 앞으로만, VALIDATED는 같은 digest의 PASS 보고서, PUBLISHED는 독립 승인이 있어야 한다(trigger). 승인은 reviewer≠author CHECK와 version·author 복합 FK.
- **저장**: oracle과 private 파일은 private store에만 둔다. 학습자 API는 PUBLISHED 버전의 공개 manifest에서 지정한 필드만 만든다. 이전 출판 버전은 고정된 Session을 위해 `versionId`로 계속 조회된다.
- **도구**: 운영 UI 없이 CLI(`:content:cli`)와 `/ops/v1` 내부 API.

## 비교한 대안

- 압축 파일 바이트 서명: 재압축만으로 digest가 바뀐다.
- sigstore/cosign 일괄 도입: 키리스 서명·투명성 로그 운영이 필요하다. 이미지 서명 단계(T06)에서 검토한다.
- DB에 oracle 저장: learner API와 같은 저장소 경계에 비밀이 놓인다.

## 비용과 위험

- `trusted-keys`가 설정에 있으므로 키 교체·폐기 절차가 필요하다(D-16).
- 누출 탐지는 oracle의 특정 필드(hidden test id, mutant, verifier 조건, secretRef, referencePatchRef)와 private 파일 바이트 일치 기준이다. 의역·부분 누출은 탐지하지 못해 사람 검수가 남는다.
- 테스트는 test-only scripted verifier로 출판 경로를 실행한다. `prod`는 `strong-runtime` 외 verifier를 거부한다.
- 승인자 디렉터리·2인 승인 인력(D-11)은 아직 없다. operatorId가 다르면 다른 사람으로 본다.

## 검증 증거

- `ContentValidationTest` 6건: 예제는 placeholder·publishable·서명 때문에 FAIL(구조·분리는 PASS), runtime 없이는 INCOMPLETE, digest의 순서 무관·내용 민감, 서명 변조·미신뢰 키 거절, oracle 값·필드·파일 누출 탐지, placeholder·경로 traversal 차단
- `ContentCliTest`: keygen(0600, 개인키 미출력)·sign·validate, 변조 후 실패
- `ContentPublishingTest` 6건(scripted verifier): 등록→검증→독립 승인→출판, 공개 API·보고서·로그에 oracle 값 없음, 미서명·변조 번들 승인 불가, 자기 승인 API 403·DB CHECK, 역할 분리, 버전 고정·이전 버전 조회·차단 후 숨김·재개 불가, 예제 출판 불가
- `ContentGateWithoutRuntimeTest` 2건: 기본 설정에서 완벽한 번들도 INCOMPLETE·DRAFT, prod에서 test verifier 거부
- 미검증: 실제 runtime verifier, 이미지 registry 서명, 키 교체, Web build 산출물 검사(Web 없음)

## 결과와 되돌리는 조건

strong-runtime verifier가 생기면 `accepted-verifiers` 기본값이 실제로 동작한다. 이미지 서명 방식을 정하면 manifest에 서명 참조를 추가하고 runner에서 검증한다.

## 영향을 받는 문서·계약·테스트

`contracts/scenario-manifest.schema.json`, `contracts/private-oracle.schema.json`, `examples/*`, `V5__content_publishing.sql`, 팩 08·14·15·19, `:content:{format,cli}`, `:control-plane:catalog`, [T03](../development/T03.md)
