# SecDrill API 명세

MVP 공개 계약은 [OpenAPI](../contracts/openapi.yaml)에 정의한다. 아래는 구현 규칙과 추가 운영·내부 계약이다. 기본 prefix는 `/v1`, JSON UTF-8, UUID 식별자, UTC RFC3339다.

## 인증과 공통 동작

개인 웹 로그인은 OIDC provider에서 확인하고 플랫폼의 opaque auth session으로 연결하는 제안을 사용한다. access session은 15분, refresh는 7일·회전·reuse 감지를 적용한다. browser cookie는 HttpOnly/Secure/SameSite, mutation은 CSRF token과 Origin 검사로 보호한다. OpenAPI cookieAuth는 access session cookie이며 운영자 API는 별도 workload/operator bearer다. provider 선택은 ADR-009의 미결정 항목이다.

POST mutation은 `Idempotency-Key` UUID를 받는다. owner+route+key로 24시간 저장하고 canonical body digest가 다른 재사용은 409 `IDEMPOTENCY_CONFLICT`다. 상태 변경은 body expectedVersion으로 CAS한다. 원래 응답의 재반환은 version 충돌 검사보다 우선한다.

| 메서드와 경로 | 입력 | 성공 | 주요 오류 |
|---|---|---|---|
| GET /scenarios | mode, cursor, limit(1~100) | 200 items,nextCursor | 422 filter |
| GET /scenarios/{id} | versionId? | 200 공개 사건 상세 | 404 unpublished/private |
| POST /sessions | scenarioVersionId, mode, parentSessionId? | 201 Session | 422 unsupported mode |
| GET /sessions/{id} | — | 200 Session | 404 |
| POST /sessions/{id}/labs | expectedVersion | 202 Lab | 429 quota,409 state |
| POST /sessions/{id}/submissions | kind, content, expectedVersion | 202 Submission | 422 input,413 size |
| GET /submissions/{id} | — | 200 verdict/progress | 404 |
| POST /sessions/{id}/actions | type,parameters,expectedVersion | 200 seq/version/state | 409 stale,422 action |
| POST /sessions/{id}/hints | challengeId,level | 200 Hint | 422 unknown,429 limit |
| POST /sessions/{id}/finish | expectedVersion | 202 Session | 409 missing gates |
| POST /sessions/{id}/stop | expectedVersion | 202 Session | 409 terminal |
| GET /sessions/{id}/evidence | afterSeq,limit | 200 items,nextSeq,hasMore | 404 |
| GET /sessions/{id}/report | — | 200 Report | 409 not ready |
| GET /sessions/{id}/replay | fromSeq,toSeq | 200 manifest/observations | 422 range |
| GET /skills/me | policyVersion? | 200 projections | 401 |
| POST /exports | sessionId? | 202 export job | 429 |
| POST /deletion-requests | scope, confirmationToken | 202 receipt | 422,401 |

Submission kind는 `FLAG`, `OBJECTIVE`, `PATCH`, `DETECTION`, `POSTMORTEM`이다. 각 content의 정확한 구조는 OpenAPI의 discriminator oneOf를 따른다. FLAG 오답은 HTTP 오류가 아니라 완료된 FAIL evaluation이다. raw flag를 echo하지 않는다. FLAG 요청은 서버가 메모리 안에서 HMAC 검증 후 jobId·challengeId·검증 결과를 가진 서명된 private receipt를 만들고 그 참조만 저장한다. 비동기 verifier는 receipt와 독립 목표 관측을 확인한다. 최초 입력은 request digest 계산 뒤 폐기하고 raw flag를 DB·artifact·Outbox에 보관하지 않는다.

MVP inline PATCH 제출은 다른 JSON 요청과 같이 총 256 KiB 제한이다. 5 MiB 압축/20 MiB 해제 한도는 후속 bundle 업로드의 자원 상한이며 현재 공개 API가 그 크기의 inline 요청을 허용한다는 뜻이 아니다. 큰 저장소 과제는 scoped upload 완료·digest 검증 계약을 추가한 뒤 지원한다. export/deletion 비동기 receipt의 pollPath는 본인 job을 조회하는 `/v1/async-jobs/{id}`다. export 완료 응답의 downloadPath는 owner 검사를 하는 플랫폼 경로이고 raw store signed URL을 장기 보관하지 않는다.

## 페이징과 실시간

카탈로그 cursor는 정렬키 publishedAt+id와 filter digest를 서명한 opaque 값이다. Evidence는 immutable seq 기반 afterSeq를 사용한다. SSE `/sessions/{id}/stream`은 cookie 인증으로 접속하고 event id에 seq를 사용한다. `Last-Event-ID` 이후부터 권한 필터된 이벤트를 제공하며 보관 범위 밖이면 410과 REST 재동기화 안내를 반환한다. SSE가 없으면 동일 REST evidence endpoint로 backoff polling한다.

## 오류 봉투

`{code,message,requestId,retryable,details}`. 400 malformed JSON, 401 로그인 필요, 403 자기 자원이지만 허용되지 않은 운영, 404 존재하지 않거나 타인 자원, 409 상태·멱등 충돌, 413 크기, 422 의미 검증, 429 한도, 503 플랫폼 일시 오류를 사용한다. details는 필드 오류·latestVersion·missingGates만 포함하고 내부 stack·oracle·비밀은 제외한다.

## 내부 계약

`POST /internal/jobs/{id}/claim`은 workload identity, attempt, workerId로 lease와 fencing token을 반환한다. heartbeat는 token 일치 시 30초 연장한다. `POST /internal/jobs/{id}/result`는 token, resultDigest, artifactRefs, verdictSummary를 받아 202 또는 stale 409를 반환한다. ingest는 job에 허용된 객체 key·크기·digest만 수신한다. Runner는 arbitrary URL fetch나 DB 접근 권한이 없다.

운영 재채점은 별도 `/ops/rejudge-requests`의 dry-run·approve·execute로 나누고 출판은 `/ops/scenario-versions/{id}/approve`를 사용한다. MVP 공개 OpenAPI에 운영자·내부 endpoint를 포함하지 않는 이유는 독립 인증과 네트워크 경계를 유지하기 위해서다. 구현 전에 각각 전용 스키마를 추가한다.
