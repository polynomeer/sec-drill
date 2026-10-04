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
