# Architecture Decision Records

구현 중 검토를 거친 실제 설계 결정을 기록한다. 설계 팩의 [ADR 초안 목록](../../SecDrill-docs/docs/29-adr-index.md)(ADR-001~015, 모두 Proposed)은 출발점이며, spike와 리뷰를 거친 결정만 여기에 별도 파일로 옮긴다.

## 규칙

- 파일 이름: `NNNN-short-title.md`(4자리 일련번호). 팩 초안을 옮길 때는 본문에 원래 ID(예: ADR-003)를 적는다.
- 상태: `Proposed` → `Accepted` / `Rejected` / `Superseded by NNNN`. 소유자 리뷰 없이 Accepted로 표시하지 않는다.
- 검증하지 않은 조건은 "미검증"으로 적는다.
- 영향을 받는 설계 문서·계약·테스트를 링크하고, 같은 변경에서 해당 문서를 개정한다.
- 템플릿: [0000-template.md](0000-template.md)

## 목록

| 번호 | 제목 | 상태 | 원 초안 |
|---|---|---|---|
| [0001](0001-control-plane-stack-and-migrations.md) | Control Plane 개발 스택과 migration | Proposed | ADR-001 일부 |
| [0002](0002-learner-and-operator-authentication.md) | 학습자·운영자 인증 | Proposed | ADR-009 |
| [0003](0003-canonical-digest-and-evidence-hash.md) | Canonical digest와 Evidence hash chain | Accepted | — |
| [0004](0004-outbox-rabbitmq-and-job-leases.md) | Outbox·RabbitMQ 전달과 job lease | Proposed | ADR-003, ADR-004 |
| [0005](0005-evidence-trust-artifacts-and-runtime-role.md) | Evidence 신뢰 규칙, private Artifact, 런타임 DB 역할 | Proposed | ADR-006 |
| [0006](0006-content-bundles-signing-and-publish-gate.md) | 콘텐츠 번들, 서명, 출판 게이트 | Proposed | ADR-010 |
| [0007](0007-lab-lifecycle-local-trusted-runtime-and-gateway.md) | Lab 수명, local-trusted runtime, workload identity, Lab Gateway | Proposed | ADR-002 일부 |
| [0008](0008-ctf-flags-objective-observation-and-demo-results.md) | CTF 플래그, 독립 목표 관측, 데모 결과 표시 | Proposed | — |
| [0009](0009-python-patch-grading-and-supervisor.md) | Python 패치 채점, 분리된 grading 환경, 외부 supervisor | Proposed | ADR-015 |
| [0010](0010-detection-dsl-and-incident-model.md) | 탐지 DSL 평가와 사고 대응 모델 | Proposed | ADR-011 일부 |
| [0011](0011-learner-workspace-web.md) | 학습자 작업 공간 Web | Proposed | ADR-012 일부 |
| [0012](0012-reports-replay-skills-recommendations.md) | 리포트, Replay, 스킬 projection, 추천 | Proposed | ADR-007, ADR-013 |
