import { ApiError, type Evaluation, type Submission } from "../api/client";
import { safeText } from "../text";

export const GATE_LABELS: Record<string, string> = {
  objective_confirmed: "목표 확인(서버가 독립 관측)",
  detection_evaluated: "탐지 규칙 채점",
  action_applied: "대응 액션 적용",
  patch_verified: "패치 VERIFIED",
  postmortem_submitted: "회고 제출",
  root_cause_reported: "원인 보고",
  flag: "플래그",
  objective: "목표 관측",
  compile: "컴파일",
  security: "보안 테스트",
  regression: "정상 회귀 테스트",
  detection: "탐지 기준(숨은 holdout)",
  resource_limit: "자원 한도",
};

export const label = (key: string) => GATE_LABELS[key] ?? key;

/** Platform trouble and learner mistakes look different and say different things (prompt 11). */
export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  if (error instanceof ApiError && error.kind === "request") {
    const fields = error.details?.fieldErrors ?? [];
    const missing = error.details?.missingGates ?? [];
    return (
      <div className="notice notice-request" role="alert" data-kind="request">
        <p>
          <strong>요청을 처리할 수 없습니다.</strong> {safeText(error.message, 300)}
        </p>
        {missing.length > 0 && (
          <ul aria-label="아직 충족되지 않은 완료 조건">
            {missing.map((gate) => (
              <li key={gate}>{label(gate)}</li>
            ))}
          </ul>
        )}
        {fields.length > 0 && (
          <ul aria-label="입력 문제">
            {fields.map((field, index) => (
              <li key={index}>
                <code>{safeText(field.field, 120)}</code>: {safeText(field.message, 300)}
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  }
  return (
    <div className="notice notice-platform" role="alert" data-kind="platform">
      <p>
        <strong>플랫폼 문제로 요청을 완료하지 못했습니다.</strong> 학습자의 실패가 아니며 작성한 메모와 이미 접수된 제출은 보존됩니다. 잠시 후 다시
        시도해 주세요.
      </p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  );
}

/** What a result means, without hidden inputs: verdict, gate categories and the demo flag only. */
export function EvaluationSummary({ submission }: { submission: Submission }) {
  const evaluation: Evaluation | undefined = submission.evaluation;
  if (!evaluation) {
    return (
      <p className="pending" data-state="pending">
        채점 중입니다({submission.status}). 결과가 나오면 알려 드립니다.
      </p>
    );
  }
  const failedGates = evaluation.gates.filter((gate) => gate.result === "FAIL");
  return (
    <div className={`result result-${evaluation.verdict.toLowerCase()}`} data-verdict={evaluation.verdict}>
      {evaluation.verdict === "PASS" && <p>✔ 통과했습니다.</p>}
      {evaluation.verdict === "FAIL" && (
        <p>
          ✖ 아직 기준을 넘지 못했습니다{failedGates.length > 0 ? `: ${failedGates.map((gate) => label(gate.key)).join(", ")}` : ""}. 수정해서 다시 제출할
          수 있습니다.
        </p>
      )}
      {evaluation.verdict === "SYSTEM_ERROR" && (
        <p>
          ⚠ 플랫폼이 판정을 확정하지 못했습니다(학습자 실패가 아님). 제출은 보존되며 점수에 불이익이 없습니다. 같은 내용으로 다시 제출해도 됩니다.
        </p>
      )}
      <ul className="gates" aria-label="채점 기준">
        {evaluation.gates.map((gate) => (
          <li key={gate.key} data-gate={gate.key} data-result={gate.result}>
            {gate.result === "PASS" ? "✔" : gate.result === "FAIL" ? "✖" : "?"} {label(gate.key)}: {gate.result}
          </li>
        ))}
      </ul>
      {evaluation.dimensions.length > 0 && (
        <ul className="dimensions" aria-label="지표">
          {evaluation.dimensions.map((dimension) => (
            <li key={dimension.key}>
              {safeText(dimension.key, 80)}: {dimension.score.toFixed(1)}%
            </li>
          ))}
        </ul>
      )}
      {evaluation.demo && <p className="demo">데모 결과: 격리가 검증되지 않은 환경의 결과로, 공식 결과가 아닙니다.</p>}
    </div>
  );
}
