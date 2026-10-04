# 8. T07 첫 사건 CTF 수직 기능 구현

SecDrill의 첫 실제 수직 기능을 완성하라.

03-mvp-scope, 05-learning-loop, 09-ctf-wargame-guide,
20-execution-grading을 읽고 tenant leak 사건 하나를 구현하라.

흐름:
로그인 → 사건 선택 → 고정 버전 Session 생성 →
Lab 준비 → 합성 목표 접근 → 플래그 제출 →
독립 목표 검증 → Evaluation·Evidence → 결과 → Lab 종료.

세션별 플래그를 서버 비밀에 바인딩하고
raw flag를 DB·로그·Outbox에 보관하지 않는다.
사용자 stdout이나 자체 성공 JSON으로 목표를 인정하지 않는다.

검증:
정상 소유자 접근,
합성 타인 리소스 접근의 목표 확인,
다른 Session 플래그 거절,
중복 제출·중복 결과,
oracle 비노출,
Lab 종료와 자원 회수.

UI는 이 흐름을 수행할 최소 화면만 구현하라.
실제 격리가 검증되지 않은 환경의 결과는 fake/demo로 표시하라.
