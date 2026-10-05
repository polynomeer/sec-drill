# 0007 Lab 수명, local-trusted runtime, workload identity, Lab Gateway

- 상태: Proposed (T04·T06 개발 경로 구현. strong runtime(D-10)·mTLS(D-17)·이미지 서명(D-16) 전 재검토)
- 날짜: 2026-10-05
- 담당: 프로젝트 소유자
- 원 초안: ADR-002(공격 Lab는 Session microVM) 일부. Gateway는 초안 ADR이 없고 11·17의 요구를 따른다
- 관련: T04, T06, FR-03, NFR-02, [11](../../SecDrill-docs/docs/11-architecture.md), [13](../../SecDrill-docs/docs/13-state-machines.md), [17](../../SecDrill-docs/docs/17-sandbox-isolation.md), [18](../../SecDrill-docs/docs/18-threat-model.md), [19](../../SecDrill-docs/docs/19-iam.md)

## 문제와 제약

Lab은 생성·준비·종료가 비동기이고 취소와 생성 완료가 경합한다. 회수는 이름이 아니라 소유권으로 판단해야 하고 Control 장애 중에도 hard TTL이 지켜져야 한다. 17은 학습자 Lab에 microVM(`lab-strong`)을 요구하지만 개발 호스트(Apple M1 Max, macOS, Docker Desktop 28.0.4 linuxkit)에는 KVM이 없어(`/dev/kvm` 없음) Firecracker를 실행할 수 없다. 격리 수준을 낮춘 경로를 완료로 표시하면 안 된다.

## 선택

- **상태**: 관측 `state`와 원하는 상태 `desired_state`를 분리한다. 종료 요청은 한 UPDATE로 desired=TERMINATED와 state=TERMINATING을 함께 바꾸고 사유·시각을 남긴다. DB CHECK가 READY⇒desired RUNNING, TERMINATED⇒cleanup 확인·receipt를 강제한다. 생성 callback은 desired가 TERMINATED면 `TERMINATE`를 돌려줘 runner가 바로 회수하게 한다(late callback).
- **작업**: PROVISION·CLEANUP은 기존 job lease·fencing(ADR 0004)을 쓴다. CLEANUP은 Lab을 만든 runner에게만 배정한다. 아직 claim되지 않은 PROVISION은 취소하고 runtime 없이 닫는다(`never-created` receipt).
- **한도**: 사용자 활성 Lab 1개(기존 partial unique), pool은 advisory lock 안에서 `cleanup_confirmed_at`이 없는 Lab 수로 계산(기본 20). CLEANUP_FAILED도 한도를 점유한다.
- **수명**: idle 15분(gateway activity로만 연장, hard 상한), hard 60분. 1분 sweeper가 TTL·소진된 provisioning·cleanup 재시도(30초 지연)·5분 초과 미회수 감사 경보(Lab당 1회)를 처리한다. Agent는 runtime label의 hard expiry로 Control 없이 회수한다.
- **소유권**: runtime과 network에 labId·generation·runnerId·hard expiry와 runner 키 HMAC 서명 label을 붙이고, 서명이 맞는 것만 나열·삭제한다. 시작 시·주기적 reconcile에서 Control이 원하지 않는 runtime은 회수하고, Control이 살아 있다고 보는데 runner에 없는 Lab은 RUNTIME_LOST로 닫는다.
- **workload identity**: `runner_credentials`의 단기(≤24시간) bearer, hash만 저장, kind AGENT·GATEWAY. `/internal/**` 전용 filter chain에서 AGENT는 lab-jobs·reconcile, GATEWAY는 gateway 경로만. Agent는 DB 자격증명이 없고 Control 내부 API만 호출한다(모듈 경계 검사).
- **local-trusted adapter**: Docker CLI argv(셸 문자열 없음). Lab마다 `--internal` network, read-only root, `/tmp` tmpfs, cap-drop ALL, no-new-privileges, **명시적 seccomp profile**, uid 65534, PID·memory(swap 동일)·CPU 제한, host mount·socket·device·published port 없음. exec 출력은 동시에 drain하고 1 MiB에서 자른다.
- **seccomp**: Docker Desktop daemon은 기본이 `seccomp=unconfined`이며 실제로 컨테이너가 `Seccomp: 0`, 비특권 user namespace 생성(`unshare -U`)이 성공했다. 그래서 adapter가 `local-trusted-seccomp.json`(namespace·mount·module·ptrace·bpf·keyring·io_uring 등 denylist, clone의 namespace flag 차단, clone3는 ENOSYS)을 항상 지정하고, 시작 직후 PID 1의 `Seccomp: 2`를 확인하지 못하면 runtime을 지우고 실패한다(fail closed).
- **Gateway**: 별도 프로세스·별도 origin. Control은 60초·1회용 Ed25519 connect token(labId·generation·owner·exp·nonce, JCS claims)을 서명하고 Gateway는 공개키만 가진다. Gateway cookie `lab_access`는 HttpOnly·Secure·SameSite=Strict. 요청마다 Control에 Lab이 READY·같은 generation·같은 owner인지 묻고, Control이 보고한 endpoint가 allowlist에 맞을 때만 proxy한다. CONNECT·absolute-form은 거절, Cookie·Authorization·forwarding header는 upstream에 보내지 않고, Lab이 `lab_access`를 덮어쓰는 Set-Cookie는 버린다. `prod`는 서명 키, https gateway, 플랫폼 origin과 다른 host를 요구한다(cookie는 port가 아니라 host 단위).

## 비교한 대안

- 상태 하나로 의도와 관측을 함께 표현: 취소 뒤 도착한 생성 완료를 구분할 수 없다.
- 이름 prefix로 회수: 다른 runner나 사용자가 만든 같은 이름을 지울 수 있다.
- Docker 기본 seccomp에 의존: 이 호스트에서는 적용되지 않았다. Moby 기본 allowlist profile 복제는 유지 부담이 크고 local-trusted가 강한 격리가 아니므로 denylist로 시작했다.
- Gateway를 Control Plane 안에 둠: 같은 host면 플랫폼 cookie가 Lab 요청에 실리고 Lab 응답이 플랫폼 origin을 오염시킬 수 있다.
- mTLS workload identity: 19의 목표지만 노드 등록·인증서 발급 체계(D-17)가 필요하다. 지금은 단기 bearer와 kind 범위로 대신한다.

## 비용과 위험

- **local-trusted는 강한 격리가 아니다.** 컨테이너는 host(linuxkit VM) kernel을 공유하고 Agent가 Docker daemon을 조작한다. seccomp는 denylist라 allowlist보다 약하다. 외부 파일럿·학습자 공격 Lab에 사용하지 않는다.
- 17의 local-trusted 행은 rootless container를 적었지만 이 호스트는 Docker Desktop의 일반 daemon이다(컨테이너 프로세스만 비root). rootless daemon은 미검증이다.
- Docker Desktop은 host에서 internal network로 라우팅하지 않으므로 Gateway→Lab 실제 네트워크 경로는 검증하지 않았다(Gateway 테스트는 로컬 upstream 사용). 운영에서는 Gateway가 runner 내부 network에 있어야 한다.
- connect nonce와 gateway session은 Gateway 메모리에만 있다. 다중 인스턴스·재시작 시 재사용 방지 범위가 인스턴스로 줄어든다.
- workload bearer 유출 시 24시간 이내 같은 kind 범위 호출이 가능하다. 폐기(`revokeRunner`)는 있으나 자동 회전은 없다.
- 이미지 서명 검증 전이다(D-16). 테스트 이미지는 digest 고정만 한다.

## 검증 증거

- `LabLifecycleTest` 11건(in-memory runtime, 실제 내부 API): 요청→생성→중지→receipt·LabTerminated·quota 해제, 중복·사용자 한도, claim 전 취소, 생성 중 취소의 late callback 회수·감사, 다른 runner·만료 lease의 STALE, idle·hard TTL과 activity의 idle만 연장, orphan 회수와 RUNTIME_LOST, provisioning 실패, cleanup 실패의 quota 유지·지연 재시도·경보 1회·회수, 운영 중지·connect 권한, workload kind 범위
- `LabPoolQuotaTest`: pool 한도가 사용자 간 공유
- `LocalTrustedIsolationTest` 6건(busybox digest 고정, 실제 Docker): hardening 설정·seccomp mode 2·`unshare -U/-n/-m` 거절·mount/socket/host path 없음·capability 0, 외부 IPv4·IPv6·DNS·metadata·host 경유 Control·다른 Lab(이름·IP) 차단과 자기 app 양성 대조, PID 64 제한·출력 1 MiB, memory 64 MiB에서 flood 프로세스 OOM kill(137)·Lab 생존, Control 장애 중 local hard TTL, 취소 뒤 생성 runtime 회수·위조 label 미삭제
- `LabGatewayTest` 4건: connect token 1회용·변조·다른 키 거절과 cookie 속성, credential header 제거·Lab의 access cookie 덮어쓰기 차단, CONNECT(JDK server 400)·absolute-form(405) 거절, 중지 후 403·cookie 삭제, allowlist 밖 endpoint 403
- 미검증: `lab-strong` microVM 전체, rootless daemon, Linux host의 Docker, Gateway→runner network 경로, 터미널 websocket, 이미지 서명, mTLS, guest escape 시 runner quarantine, 다중 Gateway

## 결과와 되돌리는 조건

strong runtime을 정하면(D-10) `RuntimeAdapter`의 새 구현을 추가하고 같은 격리 테스트를 그 runtime에 대해 통과해야 외부 공개 후보가 된다. local-trusted adapter는 `prod`·파일럿에서 쓰지 않는다. mTLS(D-17)가 생기면 bearer는 폐기한다.

## 영향을 받는 문서·계약·테스트

`V6__lab_lifecycle.sql`, `contracts/schema.sql`·`enums.json`(LabDesiredState·LabTerminateReason·WorkloadKind)·`openapi.yaml`(connectLab·LabConnect)·`fixtures/api.json`, 팩 13·14·15, `:control-plane:lab`, `:execution:{protocol,agent}`, `:lab-gateway`, `:control-plane:identity`(workload chain), [T04_T06](../development/T04_T06.md)
