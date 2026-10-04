# 6. T03 콘텐츠 저작·검증·출판 기반 구현

SecDrill T03을 구현하라.

08-content-guide, 09-ctf-wargame-guide, 29-adr-index,
examples와 공개·비공개 계약을 읽어라.

다음을 구현하라.

- 공개 manifest와 private oracle의 분리
- 버전과 canonical digest
- 이미지·콘텐츠 서명 검증
- 참조 해답·mutant·seed 검증 인터페이스
- 검증 보고서
- 작성자와 승인자의 분리
- 출판·차단·버전 고정

아직 실제 runtime 검사가 없다면 검증 결과를 구분하고,
검증하지 않은 번들이 출판 게이트를 통과하지 못하게 하라.

예제의 placeholder digest나 publishable=false를
실제 출판 가능한 데이터로 임의 변경하지 않는다.

공개 API·Web build·로그에서 oracle·정답·플래그 비밀이
노출되지 않는지 검사하라.
운영 화면은 필요하지 않으면 CLI·내부 API로 시작하라.
