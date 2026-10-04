@AGENTS.md

## Claude Code 전용

- 이 저장소의 공통 규칙은 위에서 가져온 `AGENTS.md`다. 여기에는 Claude Code 고유 사항만 둔다.
- 새 대화에서는 [WORKFLOW](docs/development/WORKFLOW.md)의 "새 대화에서 읽을 최소 파일"부터 확인한다.
- 검증: `.venv/bin/python scripts/check.py`(문서·계약), `./gradlew check`(빌드·테스트, JDK 21·Docker 필요). 환경 준비는 [README](README.md).
- 공유 설정은 `.claude/settings.json`이다. 개인 설정은 `.claude/settings.local.json`이나 `CLAUDE.local.md`에 두며 둘 다 Git에서 제외된다.
- 편집 후 hook(`.claude/hooks/check-edited-file.py`)이 JSON 문법만 검사한다. 오류가 보고되면 다음 작업 전에 고친다.
