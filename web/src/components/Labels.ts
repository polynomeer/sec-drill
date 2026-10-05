// Wording shared by the report, replay and skill pages (07, 10, 22).
export const REPRESENTATION: Record<string, string> = {
  OBSERVED: "관측 기록",
  SIMULATED: "모델 재계산(SIMULATED)",
  SERVER_VERIFIED: "서버 확인",
  USER_REPORTED: "학습자 보고",
};

export const STATUS: Record<string, string> = {
  PASS: "✔ 통과",
  FAIL: "✖ 미통과",
  INCONCLUSIVE: "? 판정 보류(플랫폼)",
  NOT_ATTEMPTED: "– 시도하지 않음",
  NOT_EVALUATED: "– 평가 범위 밖",
};

export const DIMENSIONS: Record<string, string> = {
  attack: "취약 경로 재현·영향",
  observation: "관측·조사",
  detection: "탐지",
  response: "대응",
  patch: "근본 수정",
  regression: "정상 회귀",
  postmortem: "회고",
};

export const EXPOSURE: Record<string, string> = {
  INDEPENDENT: "도움 없이 수행",
  GUIDED: "힌트나 이전 Session의 도움을 받음",
  SOLUTION_EXPOSED: "해설을 본 뒤 수행",
};

export const REASONS: Record<string, string> = {
  EVIDENCE_GAP: "이 역량의 근거가 아직 부족합니다",
  LOW_INDEPENDENT_SUCCESS: "독립 성공률이 낮은 역량을 다룹니다",
  NEW_FAMILY: "처음 보는 사건 계열입니다",
  PREFERRED_MODE: "자주 하는 모드로 할 수 있습니다",
  TRANSFER_AFTER_HELP: "도움을 받은 뒤라 다른 계열로 전이를 확인합니다",
};

export const LEVELS: Record<string, string> = {
  UNKNOWN: "미측정(근거 부족)",
  DEVELOPING: "발전 중",
  PRACTICING: "연습 중",
  DEMONSTRATED: "입증됨",
};

export const CONFIDENCE: Record<string, string> = { LOW: "낮음", MEDIUM: "보통", HIGH: "높음" };

export const ARTIFACT: Record<string, string> = {
  NONE: "",
  AVAILABLE: "원본 있음",
  EXPIRED: "원본 만료: 요약과 digest만 남아 있습니다",
  DELETED: "원본 삭제됨: 요약과 digest만 남아 있습니다",
};
