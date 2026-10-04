# SecDrill 기여 절차

공통 규칙은 [AGENTS.md](AGENTS.md), 단계별 작업 흐름은 [WORKFLOW](docs/development/WORKFLOW.md)를 따른다. 이 문서는 검증·리뷰·커밋 규칙을 정한다.

## 작업과 검증

1. 관련 요구사항 ID(FR/NFR)·Task(T01~T14)·ADR을 확인하고 변경 범위를 정한다.
2. 변경을 작게 나눈다. 한 PR은 0.5~2일 분량의 검토 가능한 단위를 목표로 한다(`30` 구현 계획).
3. 변경에 맞는 검증을 실행하고 결과를 PR에 붙인다. 현재 공통 검증은 다음 하나다.

   ```bash
   .venv/bin/python scripts/check.py --strict
   ```

   ```bash
   ./gradlew check
   ```

   앞은 문서·계약·설정, 뒤는 빌드·단위·경계·PostgreSQL 테스트다. 의존성을 바꾸면 `./gradlew dependencies --write-locks`로 lockfile을 갱신해 같은 변경에 포함한다.
4. 실행하지 못한 검증(실제 PostgreSQL, strong runtime, 성능, 카오스 등)은 "미검증"으로 적는다.

## 커밋 메시지

[Conventional Commits](https://www.conventionalcommits.org/) 기반 형식을 사용한다.

```text
type(scope): summary
```

| type | 용도 |
|---|---|
| `feat` | 사용자·운영자에게 보이는 새 동작 |
| `fix` | 잘못된 동작 수정 |
| `docs` | 문서만 변경 |
| `refactor` | 동작 변화 없는 구조 변경 |
| `test` | 테스트 추가·수정 |
| `build` | 빌드 시스템·의존성 |
| `ci` | CI 설정 |
| `chore` | 그 밖의 유지 작업 |
| `perf` | 성능 개선 |
| `revert` | 이전 커밋 되돌림 |

권장 scope: `identity`, `catalog`, `session`, `lab`, `submission`, `evaluation`, `evidence`, `replay`, `simulation`, `recommendation`, `execution`, `web`, `contracts`, `content`, `infra`, `docs`, `tooling`.

규칙:

- 커밋 메시지는 제목·본문·footer 모두 **영어**로 쓴다. 설계 문서와 PR 본문의 언어와 관계없이 적용한다.
- 일반적인 Git 메시지 관례를 따른다.
  - 제목은 명령형 현재 시제(`add`, `fix`; `added`, `fixes` 아님)로 쓴다. `type(scope):` 뒤 summary는 소문자로 시작하고 마침표를 붙이지 않는다.
  - 제목은 간결하게 쓰고 72자 이내를 권장한다.
  - 제목 한 줄, 빈 줄, 본문 순서로 쓰고 본문은 72자에서 줄바꿈한다.
  - 본문은 무엇을 어떻게 했는지보다 **왜** 바꿨는지를 설명한다.
  - 이슈·PR·co-author 같은 메타데이터는 맨 끝 footer에 둔다(예: `Refs: #12`, `Co-Authored-By: ...`).
- 한 커밋은 하나의 검토 가능한 목적을 가진다. 동작 변경, 대규모 포맷 변경, 무관한 정리를 섞지 않는다.
- 복잡한 변경은 본문에 변경 이유, 계약 영향(API·이벤트·DB·enum), 검증 결과를 적는다.
- 호환성을 깨는 변경은 `type(scope)!: summary`로 쓰고 본문에 `BREAKING CHANGE:`로 영향과 이전 방법을 설명한다.

예시:

```text
docs(project): establish development guidelines
feat(session): freeze scenario versions on session creation
fix(execution): reject results from expired leases
test(evaluation): cover mandatory regression gates
```

## 커밋에 넣지 않는 것

비밀·자격증명·개인키, raw Lab 로그, 사용자 제출물, 개인 설정(`.claude/settings.local.json`, `CLAUDE.local.md`, 에디터 설정), 생성 ZIP, 로컬 환경(`.venv/`)은 커밋하지 않는다. `.gitignore`가 기본 방어선이지만 staging 전 확인을 대신하지 않는다.

## staging과 commit

- staging 전에 `git status`와 `git diff`로 변경을 확인하고 비밀·개인 경로가 없는지 본다.
- 파일을 명시해 staging한다. `git add -A`, `git add .`, `git commit -a`로 자동 staging하지 않는다.
- staging 후 `git diff --cached`로 최종 내용을 다시 확인한다.
- 에이전트는 사용자가 요청했을 때만 commit을 만든다. push·배포·새 브랜치 생성도 요청이 있을 때만 한다.
- 강제 push, 기존 커밋 재작성(amend·rebase·reset of pushed commits), 보호 브랜치 직접 push는 별도의 명시적 승인 없이는 하지 않는다.

## Pull Request와 리뷰

PR 본문은 [템플릿](.github/pull_request_template.md)을 사용한다. 문제와 변경된 동작을 먼저 설명하고, 관련 요구사항·Task·ADR, 검증, 남은 위험을 적는다.

리뷰어는 변경과 관련된 항목을 우선 확인한다.

- 상태 전이와 CAS, 동시 요청 충돌
- stale callback·fencing token, idempotency body mismatch
- owner·Artifact·Session 연결과 cross-owner 404
- Oracle·숨은 정답 노출, 비밀·PII의 로그·응답 유출
- 플랫폼 오류를 사용자 실패로 바꾸는 경로
- kill·cleanup·TTL, 개인정보 삭제

리뷰 지적의 수정은 실패를 재현하는 테스트나 fixture와 함께 한다.
