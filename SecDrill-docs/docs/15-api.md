# SecDrill API 명세

MVP 공개 계약은 [OpenAPI](../contracts/openapi.yaml)에 정의한다. 아래는 구현 규칙과 추가 운영·내부 계약이다. 기본 prefix는 `/v1`, JSON UTF-8, UUID 식별자, UTC RFC3339다.

## 인증과 공통 동작

개인 웹 로그인은 OIDC provider에서 확인하고 플랫폼의 opaque auth session으로 연결하는 제안을 사용한다. access session은 15분, refresh는 7일·회전·reuse 감지를 적용한다. browser cookie는 HttpOnly/Secure/SameSite, mutation은 CSRF token과 Origin 검사로 보호한다. OpenAPI cookieAuth는 access session cookie이며 운영자 API는 별도 workload/operator bearer다. provider 선택은 ADR-009의 미결정 항목이며 구현은 provider 중립 OIDC(Authorization Code+PKCE)다.

로그인은 `/oauth2/authorization/oidc`에서 시작하고 callback 성공 시 세 cookie를 발급한다. `access_session`(HttpOnly·Secure·SameSite=Lax·Path=/, 15분), `refresh_session`(HttpOnly·Secure·SameSite=Strict·Path=/v1/auth, 7일), `csrf_token`(Secure·SameSite=Strict, JS가 읽어 `X-CSRF-Token`으로 전송). 서버는 세 값 모두 SHA-256 hash만 저장한다. `POST /v1/auth/refresh`는 refresh를 회전하고 이전 access를 무효화한다. 이미 회전된 refresh가 다시 오면 그 로그인 전체를 `REFRESH_REUSE`로 폐기하고 401을 반환한다. `POST /v1/auth/logout`은 로그인을 폐기하고 cookie를 지운다. 폐기·만료된 access는 즉시 401이다.

모든 unsafe method(POST·PUT·PATCH·DELETE)는 `Origin`이 허용 목록과 정확히 일치해야 하며 없으면 403 `FORBIDDEN`이다. 로그인된 요청은 추가로 `X-CSRF-Token`이 해당 로그인의 csrf hash와 일치해야 한다. refresh는 Origin만 검사한다. 운영자 경로 `/ops/**`는 `Authorization: Bearer` operator token만 받고 learner cookie를 무시하며, `/v1/**`는 operator bearer를 인증 수단으로 받지 않는다. 운영자 요청은 처리 전 audit_events에 기록하고 기록 실패 시 거절한다.

다른 owner의 Session·Submission·Artifact·Report·Evidence는 존재하지 않는 자원과 같은 404 `NOT_FOUND`로 응답한다(존재 은폐). 공통 owner guard가 이를 판정하며 각 API는 구현 시 guard를 거쳐야 한다. 개발용 `POST /v1/auth/dev-login`은 `local` profile에서만 켤 수 있고, 다른 profile에서 켜면 기동이 실패한다. 공개 OpenAPI에는 포함하지 않는다.

POST mutation은 `Idempotency-Key` UUID를 받는다. owner+route+key로 24시간 저장하고 canonical body digest가 다른 재사용은 409 `IDEMPOTENCY_CONFLICT`다. 상태 변경은 body expectedVersion으로 CAS한다. 원래 응답의 재반환은 version 충돌 검사보다 우선한다. 제출 생성의 `submissions.client_request_id`는 이 Idempotency-Key 값이다.

| 메서드와 경로 | 입력 | 성공 | 주요 오류 |
|---|---|---|---|
| GET /scenarios | mode, cursor, limit(1~100) | 200 items,nextCursor | 422 filter |
| GET /scenarios/{id} | versionId? | 200 공개 사건 상세(patchPaths·actions·completionRequirements 포함) | 404 unpublished/private |
| POST /sessions | scenarioVersionId, mode, parentSessionId? | 201 Session(CREATED, 버전 고정) | 422 UNSUPPORTED_MODE, 404 unpublished |
| GET /sessions/{id} | — | 200 Session | 404 |
| POST /sessions/{id}/labs | expectedVersion | 202 Lab | 429 quota,409 state |
| POST /sessions/{id}/labs/{labId}/connect | — | 200 connectUrl,expiresAt | 409 NOT_READY,404 |
| POST /sessions/{id}/submissions | kind, content, expectedVersion | 202 Submission | 422 input,413 size |
| GET /submissions/{id} | — | 200 verdict/progress | 404 |
| POST /sessions/{id}/actions | type,parameters,expectedVersion | 200 seq/version/state(SIMULATED) | 409 stale·효과 없음,422 action·미제공,422 UNSUPPORTED_MODE(PURPLE 아님) |
| GET /sessions/{id}/detection-dataset | — | 200 training 합성 로그(label 없음) | 422 UNSUPPORTED_MODE |
| POST /sessions/{id}/hints | challengeId,level | 200 Hint(다음 레벨만, 재조회는 추가 감점 없음) | 422 unknown·순서 위반,404 |
| POST /sessions/{id}/finish | expectedVersion | 202 Session(SUBMITTED, Lab 종료 요청) | 409 MISSING_GATES |
| POST /sessions/{id}/stop | expectedVersion | 202 Session | 409 terminal |
| GET /sessions/{id}/evidence | afterSeq,limit | 200 items,nextSeq,hasMore | 404 |
| GET /sessions/{id}/report | — | 200 Report | 409 not ready |
| GET /sessions/{id}/replay | fromSeq,toSeq | 200 manifest/observations | 422 range |
| GET /skills/me | policyVersion? | 200 projections | 401 |
| POST /exports | sessionId? | 202 export job | 429 |
| POST /deletion-requests | scope, confirmationToken | 202 receipt | 422,401 |

Submission kind는 `FLAG`, `OBJECTIVE`, `PATCH`, `DETECTION`, `POSTMORTEM`이다. 각 content의 정확한 구조는 OpenAPI의 discriminator oneOf를 따른다. FLAG 오답은 HTTP 오류가 아니라 완료된 FAIL evaluation이다. challengeId는 Session에 고정된 버전의 FLAG challenge여야 하며 아니면 422다. Session당 최근 1분 오답이 10건이면 429 `RATE_LIMITED`와 `Retry-After: 60`을 반환한다. raw flag를 echo하지 않는다. FLAG 요청은 서버가 메모리 안에서 HMAC 검증 후 jobId·challengeId·검증 결과를 가진 서명된 private receipt를 만들고 그 참조만 저장한다. 비동기 verifier는 receipt와 독립 목표 관측을 확인한다. 구현: 일치 여부는 Session의 살아 있는(desired RUNNING) Lab generation별 nonce로만 계산하므로 다른 Session·종료된 Lab의 플래그는 일치하지 않는다. receipt는 flag 원문 없이 matched·labId·generation·keyVersion을 담고 HMAC으로 서명한다. 판정: 불일치 FAIL, 일치+관측 PASS, 일치+관측 없음 SYSTEM_ERROR(objective INCONCLUSIVE), 관측 수집 실패·receipt 검증 실패는 재시도 후 SYSTEM_ERROR. Evaluation의 `demo`는 fake worker나 격리가 검증되지 않은 runtime의 결과에서 true이며 Lab의 `isolationVerified`가 false이면 그 Session 결과는 모두 demo다. 최초 입력은 request digest 계산 뒤 폐기하고 raw flag를 DB·artifact·Outbox에 보관하지 않는다.

DETECTION 제출은 PURPLE·DETECTION Session에서만 받고 규칙을 21의 제한으로 수락 시 검사한다(위반은 422, 문제 경로를 fieldErrors로). 규칙과 설명은 learner artifact로 저장하고 설명은 USER_REPORTED Evidence가 된다. 채점은 Control Plane이 숨은 holdout으로 한다(일반 worker 미배정). MVP inline PATCH 제출은 다른 JSON 요청과 같이 총 256 KiB 제한이다. 구현: Session mode가 PATCH·PURPLE이고 고정된 manifest에 `patch`가 있어야 하며(아니면 422 `UNSUPPORTED_MODE`), `files`의 모든 경로가 `patch.allowedPaths`와 정확히 일치해야 한다(아니면 422, 경로를 응답에 되풀이하지 않음). 검증된 grading runtime이 없고 개발 override도 없으면 503이다. canonical bundle은 경로순 `{path, sha256, byteSize}` 목록과 explanation digest의 JCS digest이며 learner 소유 artifact(LEARNER)로 저장한다. PATCH GRADE job은 grading runtime을 가진 runner만 `kinds: [PATCH]`로 claim하고 결과는 `POST /internal/v1/grade-jobs/patch-result`로 보고한다. 5 MiB 압축/20 MiB 해제 한도는 후속 bundle 업로드의 자원 상한이며 현재 공개 API가 그 크기의 inline 요청을 허용한다는 뜻이 아니다. 큰 저장소 과제는 scoped upload 완료·digest 검증 계약을 추가한 뒤 지원한다. export/deletion 비동기 receipt의 pollPath는 본인 job을 조회하는 `/v1/async-jobs/{id}`다. export 완료 응답의 downloadPath는 owner 검사를 하는 플랫폼 경로이고 raw store signed URL을 장기 보관하지 않는다.

## 페이징과 실시간

카탈로그 cursor는 정렬키 publishedAt+id와 filter digest를 서명한 opaque 값이다(구현: 프로세스별 HMAC 키라 재시작 후 cursor는 422). Evidence는 immutable seq 기반 afterSeq를 사용한다. SSE `/sessions/{id}/stream`은 cookie 인증으로 접속하고 event id에 seq를 사용한다. 구현(T11): 1초 간격으로 원장을 읽어 `Last-Event-ID` 이후만 보내고 5분 뒤 연결을 닫으며, 클라이언트는 마지막 seq로 다시 연결한다. 보관 범위 밖 410은 아직 없다. `Last-Event-ID` 이후부터 권한 필터된 이벤트를 제공하며 보관 범위 밖이면 410과 REST 재동기화 안내를 반환한다. SSE가 없으면 동일 REST evidence endpoint로 backoff polling한다.

## 오류 봉투

`{code,message,requestId,retryable,details}`. 400 malformed JSON, 401 로그인 필요, 403 자기 자원이지만 허용되지 않은 운영, 404 존재하지 않거나 타인 자원, 409 상태·멱등 충돌, 413 크기, 422 의미 검증, 429 한도, 503 플랫폼 일시 오류를 사용한다. details는 필드 오류(fieldErrors)·latestVersion·missingGates만 포함하고 내부 stack·oracle·비밀은 제외한다. `code` 값과 코드별 HTTP 상태·retryable은 [enums.json](../contracts/enums.json)의 errorCodes catalog가 단일 기준이다. 예상하지 못한 서버 오류는 500 `INTERNAL_ERROR`이며 내부 정보를 노출하지 않는다.

## 내부 계약

`POST /internal/jobs/{id}/claim`은 workload identity, attempt, workerId로 lease와 fencing token을 반환한다. heartbeat는 token 일치 시 30초 연장한다. `POST /internal/jobs/{id}/result`는 token, resultDigest, artifactRefs, verdictSummary를 받아 202 또는 stale 409를 반환한다. ingest는 job에 허용된 객체 key·크기·digest만 수신한다. Runner는 arbitrary URL fetch나 DB 접근 권한이 없다.

채점 내부 API(AGENT): `POST /internal/v1/grade-jobs/{claim,start,heartbeat,observed}`. FLAG GRADE job은 그 Lab을 호스팅한 runner에게만 배정되고 runner는 flag 일치 여부를 받지 않은 채 target의 서버 측 접근 기록을 관측해 보고한다. 일반 worker(fake 포함)는 FLAG job을 받지 않는다. Lab 내부 API(workload bearer, `runner_credentials`): AGENT는 `POST /internal/v1/lab-jobs/{claim,start,heartbeat,provisioned,provision-failed,terminated,cleanup-failed}`와 `POST /internal/v1/labs/reconcile`, GATEWAY는 `GET /internal/v1/gateway/labs/{labId}`와 `POST /internal/v1/gateway/labs/{labId}/activity`만 호출한다. 다른 `/internal/**` 경로와 learner cookie·operator bearer는 거절한다. 모든 callback은 lease의 workerId·fencing token이 일치해야 하며 오래된 token은 `STALE`을 받는다. CLEANUP job은 그 Lab을 만든 runner에게만 배정한다. connect URL은 별도 origin Lab Gateway의 `/connect?token=`이며 token은 labId·generation·owner·만료·nonce를 담은 Ed25519 서명 값으로 60초·1회용이다. 운영 중지는 `POST /ops/v1/labs/{labId}/stop`(OPERATOR·SECURITY_ADMIN)이다. mTLS workload identity(D-17)는 미구현이다.

운영 재채점은 별도 `/ops/rejudge-requests`의 dry-run·approve·execute로 나누고 출판은 `/ops/scenario-versions/{id}/approve`를 사용한다. 콘텐츠 내부 API(operator bearer): `POST /ops/v1/content/bundles`(AUTHOR, 서명 번들 등록 → DRAFT), `POST /ops/v1/scenario-versions/{id}/validations`(AUTHOR·REVIEWER, 검증 보고서), `POST /ops/v1/scenario-versions/{id}/approve`(작성자가 아닌 REVIEWER), `POST /ops/v1/scenario-versions/{id}/quarantine`(OPERATOR·SECURITY_ADMIN·REVIEWER). 학습자 `GET /scenarios/{id}`는 PUBLISHED 버전의 공개 manifest 필드만 반환한다. MVP 공개 OpenAPI에 운영자·내부 endpoint를 포함하지 않는 이유는 독립 인증과 네트워크 경계를 유지하기 위해서다. 구현 전에 각각 전용 스키마를 추가한다.
