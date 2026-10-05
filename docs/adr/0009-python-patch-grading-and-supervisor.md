# 0009 Python 패치 채점, 분리된 grading 환경, 외부 supervisor

- 상태: Proposed (T08 구현. grading-strong runtime(D-10) 검증 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-015(dependency download 없는 Python patch MVP), ADR-005(공식 점수는 rule/hidden gates)
- 관련: T08, FR-05, [08](../../SecDrill-docs/docs/08-content-guide.md), [10](../../SecDrill-docs/docs/10-evaluation-evidence.md), [17](../../SecDrill-docs/docs/17-sandbox-isolation.md), [20](../../SecDrill-docs/docs/20-execution-grading.md), [ADR 0008](0008-ctf-flags-objective-observation-and-demo-results.md)

## 문제와 제약

첫 사건의 Python 패치를 받아 "수정됐다"를 증명해야 한다. compile도 비신뢰 실행이고, 학습 Lab과 채점 환경은 분리해야 하며, 판정은 사용자 코드가 바꿀 수 없는 supervisor가 내려야 한다. 우회·전부 거절·한 경로만 수정·클라이언트 tenant 신뢰·결과 조작 패치가 VERIFIED가 되면 출시를 막는다(03). grading-strong microVM은 이 호스트에서 실행할 수 없다(D-10).

## 선택

- **제출**: OpenAPI `PatchSubmission`(`files{path:text}`, `explanation`). Session mode는 PURPLE(PATCH 단계)이나 PATCH, 고정된 manifest에 `patch`가 있어야 한다. 경로는 `patch.allowedPaths`와 정확히 일치해야 하며 `..`·절대경로·테스트 파일·다른 소스는 422(경로를 응답에 되풀이하지 않음). 검증된 grading runtime이 없으면 503, 개발 override(`secdrill.grading.allow-unverified-isolation`, `prod` 거부)일 때만 받고 결과는 demo다.
- **canonical bundle**: 경로순 `{path, sha256, byteSize}` 목록과 explanation digest의 JCS SHA-256. 파일과 함께 learner 소유 artifact(LEARNER)로 저장하고 submission에는 artifact 참조와 `bundleDigest·fileCount`만 둔다. 채점 전 저장본 digest를 다시 확인한다.
- **분리된 grading 환경**(runner, local-trusted): job attempt마다 새로 만들고 끝나면 지운다. `--internal` network, 허용 파일만 담은 volume(network 없는 helper가 경로 인자로 기록), network 없는 compile 컨테이너(seccomp mode 2 확인, `compileall`; exit 3만 사용자 compile 오류, 그 외는 platform), 패치된 app 컨테이너, 별도 supervisor 컨테이너. 모두 Lab과 같은 hardening(읽기 전용 root, cap 없음, no-new-privileges, seccomp, 비root, PID·메모리 제한). 학습자의 Lab은 사용하지 않는다.
- **외부 supervisor**: platform 스크립트가 비공개 `hidden-tests.json`(로그인과 test별 요청·기대: `statusIn`, `idsInclude`, `idsExclude`)을 stdin으로 받아 app에 요청하고 상태 코드와 주문 id만으로 판정한다. 출력은 test id별 성공 여부뿐이다. app은 test plan을 받지 않고 supervisor 출력에 쓸 수 없으며, app의 stdout·파일·응답 속 "verdict"는 읽지 않는다.
- **판정(Control)**: oracle `hiddenTests`의 `expected`로 deny=보안, allow=회귀를 나눈다. compile 실패 FAIL/NOT_VERIFIED(gate compile), 모든 보안·회귀 통과 PASS/VERIFIED, 하나라도 실패 FAIL/NOT_VERIFIED, platform 오류·결과 누락은 재시도 후 SYSTEM_ERROR/INCONCLUSIVE, 채점 자료 누락·불일치는 content invalid SYSTEM_ERROR(재시도 없음). 학습자에게는 `compile·security·regression` gate만 보인다. `TEST_RESULT` Evidence(SUPERVISOR/OBSERVED)는 통과 수·총수와 digest만 담는다.
- **라우팅**: PATCH GRADE job은 grading runtime을 가진 runner가 `kinds:[PATCH]`로만 받는다. fake worker를 포함한 일반 worker는 FLAG·PATCH를 받지 않는다.
- **콘텐츠**: 합성 tenant-orders 앱을 `server.py`(고정)·`data.py`(고정)·`orders.py`·`authz.py`(패치 경로)로 나눴다. 비공개 자료는 `private/`에 두고 `.dockerignore`로 이미지에서 제외한다.

## 비교한 대안

- 학습 Lab 안에서 테스트 실행: 사용자가 바꾼 환경이 테스트와 결과를 조작할 수 있다(20 anti-tamper).
- app 컨테이너 안에서 pytest 실행: 같은 프로세스 공간에서 test framework를 monkeypatch할 수 있다.
- `docker build`로 job마다 이미지 생성: BuildKit 단계가 network를 가질 수 있고 registry 조회가 걸린다(테스트 JVM에서 실제로 멈췄다).
- 실패한 test id를 학습자에게 표시: 숨은 입력이 드러난다(20은 개념·범주·최소 반례만 허용).

## 비용과 위험

- **local-trusted는 grading-strong이 아니다.** 같은 커널을 공유한다. 결과는 모두 demo다.
- supervisor 요청이 일반 사용자 요청과 구분되지 않도록 특별한 header를 쓰지 않지만, 패치가 요청 패턴으로 채점을 감지해 다르게 행동하는 것을 완전히 막지는 못한다(20). hidden test를 늘리고 Session seed 변형을 쓰는 것은 T13 범위다.
- 콘텐츠 runtime verifier(참조 해답 PASS·mutant FAIL을 출판 전에 자동 확인)는 아직 없다. 이 ADR의 fixture 테스트가 같은 확인을 테스트로 수행한다. `accepted-verifiers` 게이트는 그대로다.
- 사용자에게 "최소 반례" 설명은 아직 없고 gate 범주만 보인다.
- 실패한 채점 run의 잔여 자원은 `reclaimExpiredGrading`으로 회수할 수 있으나 주기 호출은 runner 실행 진입점과 함께 연결해야 한다.

## 검증 증거

- `PatchGradingTest` 5건(실제 Docker, local-trusted): 참조 패치 VERIFIED·gate 3종 PASS·demo, egress·Docker socket이 있으면 스스로 죽는 probe 패치도 VERIFIED(=grading 환경에 출구 없음), 학습 Lab 미사용, grading 컨테이너 잔여 없음, `TEST_RESULT` Evidence, 응답·로그에 hidden test id·요청 경로 없음. 무수정·전부 거절·한 경로만 수정·클라이언트 tenant 신뢰·결과 조작·compile 오류 패치가 모두 NOT_VERIFIED이고 기대한 gate에서 실패. 허용 밖 경로 5종 422(경로 미반복), CTF Session 422, 타인 Session 404, bundle digest 순서 무관·LEARNER artifact, 일반 worker 미배정. platform 오류 3회 후 SYSTEM_ERROR/INCONCLUSIVE, 부분 결과는 재시도, 중복 결과 STALE, 채점 자료 누락은 1회 SYSTEM_ERROR
- `UnverifiedIsolationRefusedTest`: override 없으면 PATCH 503, 제출 미저장
- `CtfSafetyTest`: prod의 grading override 거부, grading-strong 외 검증 선언 거부
- 미검증: grading-strong runtime, Linux host, 채점 감지형 패치, 콘텐츠 runtime verifier, 대용량 bundle upload

## 결과와 되돌리는 조건

grading-strong runtime이 생기면 같은 `PatchGrader` 계약으로 구현을 바꾸고 이 fixture 전체를 그 runtime에서 통과해야 공식 결과가 된다. 콘텐츠 runtime verifier는 이 fixture 실행을 출판 게이트로 옮긴 것이 된다.

## 영향을 받는 문서·계약·테스트

팩 08·10·15·20, `:execution:{protocol,agent}`(`PatchTask`, `gradePatch`, `grading-driver.py`), `:control-plane:submission`(`PatchGradingService`, PATCH 수락, `GradingProperties`), `content/labs/tenant-orders`(모듈 분리, `private/`), [T08](../development/T08.md)
