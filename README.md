# SecDrill

SecDrill은 격리된 환경에서 취약점을 찾고 공격을 재현한 뒤, 탐지·대응·수정을 하고 변형 공격과 새 과제로 실력을 검증하는 보안 실전 훈련 플랫폼이다. CTF·Wargame·Purple 세 모드를 제공한다. 제품 정의는 [설계 문서 세트](SecDrill-docs/README.md)를 따른다.

## 현재 상태

개발 저장소 초기화 단계다. **제품 코드, 공격 Lab, 실행 서버는 아직 없다.** 설계 문서는 v0.1 제안 초안이며 수치·스택은 검증할 가정이다. 진행 상황은 [IMPLEMENTATION_STATUS](docs/development/IMPLEMENTATION_STATUS.md)에 있다.

## 문서 진입점

| 문서 | 내용 |
|---|---|
| [SecDrill-docs/](SecDrill-docs/README.md) | 설계 단일 출처: 문서 00~34, `contracts/`(OpenAPI·event schema·DDL), `examples/` |
| [AGENTS.md](AGENTS.md) | 모든 코딩 에이전트와 기여자가 따르는 규칙 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 검증·리뷰·커밋 규칙 |
| [docs/development/WORKFLOW.md](docs/development/WORKFLOW.md) | 작업 흐름, 새 대화에서 읽을 파일, 완료 기준, 에이전트 간 인계 |
| [docs/development/DECISIONS_REQUIRED.md](docs/development/DECISIONS_REQUIRED.md) | 결정이 필요한 항목과 권장안 |
| [docs/development/DESIGN_BASELINE.md](docs/development/DESIGN_BASELINE.md) | 설계 분류와 계약 검토 발견 사항 |
| [docs/development/T01_PLAN.md](docs/development/T01_PLAN.md) | 다음 구현 작업 |
| [docs/adr/](docs/adr/README.md) | 검토된 실제 설계 결정 |

## 준비

요구 사항: Python 3.14(검증기 고정 버전 기준), Git. 의존성은 저장소 로컬 `.venv`에만 설치한다.

```bash
python3 -m venv .venv
```

```bash
.venv/bin/python -m pip install -r requirements-dev.txt
```

```bash
.venv/bin/python scripts/check.py
```

검사는 문서·계약·설정만 확인한다. 범위와 미검증 항목은 [VERIFICATION](docs/development/VERIFICATION.md)에 있다.

Claude Code 사용자는 공유 설정 `.claude/settings.json`을 그대로 쓰고, 개인 설정은 `.claude/settings.local.json`에 둔다(Git 제외).
