# SecDrill 샌드박스와 격리 설계

보안 Lab은 사용자 코드 실행보다 넓은 공격면을 갖는다. 네트워크가 필요한 취약 앱·DB·터미널은 Session별 microVM 내부에 두고 관리·채점·다른 Lab과 분리한다. 단순 namespace를 강한 적대적 격리로 간주하지 않는다.

## 프로파일

| 프로파일 | 용도 | 격리 |
|---|---|---|
| local-trusted | 작성자 로컬 개발 | rootless containers; 외부 파일럿 금지 |
| lab-strong | 학습자 공격·탐색 | dedicated runner host의 Session microVM |
| grading-strong | 패치 컴파일·숨은 검사 | Lab과 별도 microVM, no egress |

microVM runtime은 Firecracker 계열을 우선 검증하며 gVisor는 지원 과제·위협 차이를 확인한 뒤 ADR로 선택한다. 실제 외부 파일럿은 선택한 강한 런타임을 require-isolation으로 강제하고 없으면 fail closed한다. 자동 프로세스 fallback은 하지 않는다.

## 네트워크

기본 outbound deny, Session 내부 app·DB만 허용한다. metadata endpoint, host gateway, private ranges 중 허용 Lab subnet 외부, multicast, IPv6 우회, DNS 외부 resolution을 차단한다. 합성 metadata나 외부 webhook 대상이 필요한 콘텐츠는 VM 안에 simulator로 제공한다. gateway는 인증된 allowlist runtime만 연결하고 Session 소유권·generation·TTL을 매 요청 확인한다.

Lab은 별도 origin·쿠키 scope를 사용한다. proxy는 arbitrary destination과 HTTP CONNECT를 허용하지 않는다. 터미널 transport는 제한 경로의 gateway websocket으로 제공하고 속도·출력 크기를 제한한다. 플랫폼 auth cookie가 Lab origin으로 전달되지 않도록 한다.

## 실행과 저장

비root·read-only base image·no-new-privileges·capabilities drop·seccomp·cgroup/PID 제한을 적용한다. host mount·Docker socket·device passthrough·privileged mode를 금지한다. root 권한 실습이 필요한 후속 콘텐츠도 guest 내부에만 권한을 부여하고 host와 장치 공유를 하지 않는다. artifact unpack은 traversal·symlink·zip bomb·filename collision을 검사한다.

전체 Lab 기본 2 vCPU·2 GiB·4 GiB·PID256; 공격 워크스페이스와 앱·DB가 이를 공유한다. compiler·dependency는 서명 이미지에 미리 제공하고 runtime download를 금지한다. 실행 출력은 스트리밍 중 동시에 drain하고 1 MiB 이후 truncate한다. timeout이면 runtime ID 기준 stop·kill·delete를 확인한다.

## 수명과 회수

idle 15분, hard TTL60분; gateway 차단 → 프로세스 중지 → 네트워크 삭제 → 임시 디스크 삭제 → cleanup receipt → quota 해제 순서다. control DB unavailable이어도 Agent가 local hard TTL을 강제한다. 1분 sweeper와 runner startup reconciliation이 orphan을 찾고 5분 이상 회수 실패는 운영 경보다. 이름만으로 삭제하지 않고 labId+generation+signed ownership label을 확인한다.

## 출시 검증

네트워크의 IPv4·IPv6·DNS·metadata·control plane·다른 Lab 접근 실패, mount/socket 부재, fork bomb·memory flood·output flood 제한, 취소 뒤 late provision 회수, hard TTL control outage, guest escape 의심 시 runner quarantine를 검증한다. 잔여 위험은 hypervisor·host kernel 취약점이며 runner patch·이미지 교체·개인정보 배제·호스트 재이미징으로 대응한다.
