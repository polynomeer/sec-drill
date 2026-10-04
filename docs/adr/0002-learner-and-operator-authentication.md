# 0002 학습자·운영자 인증

- 상태: Proposed (소유자가 T02 구현 방식으로 결정, provider 선정과 외부 파일럿 전 재검토)
- 날짜: 2026-10-04
- 담당: 프로젝트 소유자
- 원 초안: ADR-009(OIDC login + opaque sessions)
- 관련: T02, FR-01, NFR-03, [15 API](../../SecDrill-docs/docs/15-api.md), [18 위협모델](../../SecDrill-docs/docs/18-threat-model.md), [19 IAM](../../SecDrill-docs/docs/19-iam.md), [DECISIONS_REQUIRED](../development/DECISIONS_REQUIRED.md) D-09

## 문제와 제약

15는 OIDC 확인 후 플랫폼 opaque session, access 15분, refresh 7일·회전·재사용 감지, HttpOnly/Secure/SameSite cookie, CSRF+Origin, 별도 운영자 bearer를 제안한다. provider는 미정이다(D-09). 타인 자원은 존재를 숨겨야 한다(FR-01).

## 선택

- **로그인**: provider 중립 OIDC Authorization Code + PKCE(Spring Security). ID token의 `iss`·`sub`만 `user_identities`에 저장하고 email·profile은 저장하지 않는다. provider 설정이 없으면 OIDC 경로를 등록하지 않는다.
- **플랫폼 세션**: 로그인마다 `auth_sessions` 1행, access·refresh는 `auth_tokens`에 SHA-256 hash로 저장. refresh 회전 시 이전 access·refresh를 함께 supersede한다. supersede된 refresh가 다시 오면 그 로그인 전체를 `REFRESH_REUSE`로 폐기한다. refresh 수명은 발급 시점부터 7일이며 로그인 전체의 절대 수명 상한은 두지 않았다(가정, 아래 재검토 조건).
- **cookie**: `access_session`(HttpOnly·Secure·Lax·`/`), `refresh_session`(HttpOnly·Secure·Strict·`/v1/auth`), `csrf_token`(Secure·Strict·`/`, JS 읽기 가능).
- **CSRF**: 모든 unsafe method는 허용 목록과 정확히 같은 `Origin`이 필요하다(없으면 거절). 로그인된 요청은 `X-CSRF-Token`이 로그인의 csrf hash와 일치해야 한다. Spring CSRF는 끄고 이 규칙으로 대체한다.
- **세션 상태**: 인증은 요청 범위에만 둔다(`RequestAttributeSecurityContextRepository`, session 인증 전략 없음). HTTP session은 OIDC handshake에만 쓰고 성공 시 무효화한다.
- **운영자**: `/ops/**`는 별도 filter chain, `operator_tokens`의 bearer(hash 저장, 최대 12시간, 목적 필수)만 받는다. learner cookie는 무시하고, `/v1/**`는 operator bearer를 받지 않는다. 인증된 운영자 요청은 처리 전 `audit_events`(append-only trigger)에 기록하고 실패하면 503으로 거절한다. 발급 UI는 T14.
- **소유권**: `OwnershipGuard.requireOwned`가 Session·Submission·Artifact·Evidence·Report를 Session owner로 판정하고, 없음·타인·삭제를 같은 404 `NOT_FOUND`로 만든다.
- **개발 대체 수단**: `POST /v1/auth/dev-login`은 `secdrill.auth.dev-login.enabled`와 `local` profile이 모두 있어야 동작한다. 그 외 profile이나 `prod`와 함께 켜면 기동이 실패한다. `prod`는 OIDC 등록과 https origin만 허용한다.

## 비교한 대안

- 자체 password 로그인: provider 없이 시작할 수 있지만 password·복구 책임이 생긴다.
- JWT access token: 서버 상태가 줄지만 즉시 폐기(12의 불변식)가 어렵다.
- Spring의 기본 CSRF(token repository): 계약의 `X-CSRF-Token`+Origin 규칙과 cookie 이름을 그대로 맞추기 어렵다.

## 비용과 위험

- 매 요청 DB 조회(token hash). 부하 측정 전(27) 캐시를 넣지 않는다.
- 여러 탭이 같은 refresh를 동시에 쓰면 패자는 재사용으로 판정되어 로그아웃된다. 보안 쪽으로 기운 의도된 동작이다.
- CSRF cookie는 JS가 읽을 수 있다. Lab은 별도 origin(17)이어야 이 가정이 유지된다.
- 운영자 identity 디렉터리·발급·break-glass 승인은 아직 없다(T14).

## 검증 증거

- `AuthSessionFlowTest` 11건: 만료, 즉시 폐기, 회전과 이전 access 무효화, 재사용 시 로그인 전체 폐기, Origin·CSRF 누락·불일치·타 로그인 token 거절, cookie 속성, operator bearer의 learner 경로 거절, 서버 session 미생성
- `OidcLoginTest` 2건: mock IdP(mock-oauth2-server 6.0.4)로 PKCE(S256)·nonce 포함 code flow, 같은 `sub` 재로그인 시 같은 사용자, 위조 state 거절
- `OperatorAuthTest` 4건, `OwnershipGuardTest` 3건, `AuthSafetyTest` 3건, `DevLoginTest`·`DevLoginDisabledTest`, DB 제약 2건(live refresh 1개, hash 형식, 운영자 12시간, audit append-only)
- 구현 중 발견·수정: Spring Security의 `SessionManagementFilter`가 cookie 인증 결과를 HTTP session에 저장해 token 만료·폐기 뒤에도 `JSESSIONID`로 인증되던 결함. 회귀 테스트로 고정
- 미검증: 실제 OIDC provider, 브라우저에서의 SameSite 동작, 부하

## 결과와 되돌리는 조건

provider 선정(D-09) 시 discovery·logout(RP-initiated) 지원을 확인하고 절대 세션 수명 상한을 다시 정한다. 조직 SSO(P2)나 다중 기기 정책이 생기면 세션 모델을 재검토한다.

## 영향을 받는 문서·계약·테스트

`SecDrill-docs/contracts/{schema.sql,openapi.yaml,enums.json}`, 팩 14·15, `V2__identity.sql`, `:control-plane:identity`, `OwnershipGuard`, 위 테스트, [T02](../development/T02.md)
