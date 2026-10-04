# 검증 범위

현재 저장소에는 제품 코드가 없다. 자동 검증은 문서·계약·설정·Git 제외 규칙에 한정된다.

## 실행

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python scripts/check.py
```

`--strict`는 선택 검증기가 없어 SKIP된 항목도 실패로 처리한다. CI(`.github/workflows/contracts.yml`)는 `--strict`로 실행한다. 스크립트는 파일을 쓰지 않는다. `build_pack.py`의 `validate()`만 가져와 쓰고, 생성물을 다시 쓰는 main은 실행하지 않는다.

## 검사 목록

| 검사 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|
| 문서 번호·링크 | 팩 문서 35개 번호 00~34, 제목 접두어, 코드 펜스 짝, 팩 내부 상대 링크. 저장소 Markdown(팩 제외)의 상대 링크 | 외부 URL 생존, heading anchor, Mermaid 렌더링 |
| OpenAPI | OpenAPI 3.1 validator, 로컬 `$ref` 해석, operationId 유일성 | 실제 서버 응답, 예제 요청·응답 fixture(T01 범위) |
| 이벤트 JSON Schema | draft 2020-12 meta-schema, 정상 이벤트 1건 통과와 잘못된 payload 거절, 16의 타입 표와 schema enum·payload 규칙 일치 | 모든 타입별 정상·비정상 fixture(T01 범위), 호환성 비교 |
| enum 정합성 | 00·schema.sql CHECK·OpenAPI의 모드·단계·Session·Lab·Submission·Evaluation·Evidence·Challenge enum 10종 | 코드 상수(아직 없음) |
| 시나리오·Oracle | versionId 일치, 모드·단계·challenge kind가 계약 안, runtime이 00 기본값, 대응 액션 4종, 가중치 합 100, 플랫폼 실패 INCONCLUSIVE, publishable false. publish를 막는 placeholder 3건 보고 | 실제 이미지 digest·서명, reference patch·mutant 실행 |
| SQL 구문 | `pglast`(PostgreSQL parser)로 schema.sql 구문 분석 | **실제 PostgreSQL 적용, 제약·trigger·partial unique 동작, migration 순서** |
| 요구사항 추적 | 02의 FR/NFR ID와 acceptance matrix 17건이 일치, 참조된 Task·문서 번호 존재, required gate에 Task 존재 | 실제 테스트 존재와 통과 |
| manifest | MANIFEST.sha256의 해시가 파일과 일치, 누락·미등록 파일 | 팩 수정 뒤 생성물 재생성(WORKFLOW 절차) |
| 설정 형식 | `.claude/settings.json` JSON 문법, bypass 모드 비활성화, 전체 Bash 허용 없음, 개인 경로·토큰 패턴 없음, hook 파일 존재, `.editorconfig` | Claude Code가 규칙을 실제로 적용하는지(수동 확인 기록은 IMPLEMENTATION_STATUS) |
| Git 제외 규칙 | 비밀·개인 설정·venv·ZIP·raw 로그·제출물 샘플 경로가 제외되고 공유 파일은 추적됨 | 이미 커밋된 파일(`.gitignore`는 소급 적용 안 됨) |

## 수행하지 않은 검증

- 실제 PostgreSQL migration과 제약 동작: T01에서 고정 digest 컨테이너로 수행 예정
- 앱 build·unit·통합·E2E 테스트: 앱이 없음
- strong runtime 격리, 네트워크 egress, 자원 제한, cleanup: T06
- 성능·부하·카오스: T14, 27의 지정 하드웨어 필요
- CI 워크플로 실제 실행: push 전이라 GitHub에서 실행되지 않음
