# SecDrill 콘텐츠와 시나리오 설계 가이드

콘텐츠의 기본 단위는 취약점 이름이 아닌 사건이다. 학습자는 모르는 사건을 조사하고 같은 시스템의 보안·운영 결과를 확인한다.

## 패키지 구성

공개 manifest에는 schemaVersion, scenarioId, version, title, modes, phases, competencyTags, 시간·자원 한도, 이미지 digest, 제공 파일 목록, 허용 대상, 목표 설명이 포함된다. 비공개 oracle bundle에는 합성 목표, 공격 라벨, hidden tests, reference patch, mutants, hints/solution, rubric을 둔다. 공개·비공개 번들의 digest를 함께 서명하되 API가 비공개 파일 경로와 본문을 노출하지 않는다.

`examples/scenario.json`과 `examples/private-oracle.json`은 해당 구조를 설명한다. 예제 digest는 자리표시 값으로 출판 게이트를 통과하지 못하게 한다. 실제 번들 digest는 압축 메타데이터가 아닌 canonical manifest와 파일 digest 목록으로 계산한다.

## 저작 순서

1. 업무 배경과 학습 역량 1~3개를 고른다.
2. 자산·사용자·정상 업무·침해 목표·허용 대상을 정의한다.
3. 정상 baseline을 구현하고 취약판과 참조 수정판을 만든다.
4. 목표 검증기를 Lab 바깥에 두고 채점 관측 계약을 정의한다.
5. 정상 회귀·우회·동시성·경계 조건 테스트와 대표 mutant를 작성한다.
6. 힌트·회고 질문·전이판을 만든 뒤 seed 집합과 외부 검수자 플레이로 검증한다.
7. 두 사람 승인, 서명, canary 배포, 버전 공개를 진행한다.

## 난이도와 품질

난이도는 사전 지식, 관측 불완전성, 경로 수, 판단 비용, 시간 압박으로 분해한다. 로그가 없거나 설명이 모호한 것을 난이도로 포장하지 않는다. 각 목표에 최소 한 가지 실제 검증 경로와 복구 가능한 실패 경로가 있어야 한다.

참조 수정판은 필수 공격 100% 차단과 필수 정상 테스트 100% 통과가 필요하다. 핵심 mutant는 전부 검출되어야 한다. 추가 비핵심 mutant의 kill ratio 목표는 90%이며 동치 mutant는 별도 근거로 제외한다. 무조건 차단·한 endpoint만 수정·UUID면 안전하다고 가정하는 mutant를 포함한다.

## MVP 사건과 확장 후보

MVP 세 사건과 전이판은 03의 범위를 따른다. 후속 사건 후보는 OAuth 계정 연결 오류, CI 합성 자격증명 유출, 가상 IAM role chain, 저장소 signed URL 경계, 탐지 telemetry 공백, 공급망 패키지 검증이다. 실제 클라우드 자격증명·현실 악성코드는 제공하지 않는다.

## 업데이트 정책

채점·취약 앱·seed 로직·rubric을 바꾸면 ScenarioVersion을 올린다. 단순 카탈로그 오타·태그 정정은 catalog revision만 올린다. 이미 열린 Session은 고정 버전으로 진행하고 중대한 격리 위험이면 버전을 차단해 SYSTEM_ERROR와 재시작 안내를 제공한다. 정답 공개 뒤에는 해당 사건 계열의 노출 증거가 Transfer 추천과 독립 성공 판단에 반영된다.
