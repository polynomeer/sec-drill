import { useEffect, useState } from "react";
import { api, type Report } from "../api/client";
import { DIMENSIONS, EXPOSURE, REASONS, REPRESENTATION, STATUS } from "../components/Labels";
import { ErrorNotice } from "../components/Messages";
import { href, navigate, useLocation } from "../router";
import { safeText } from "../text";

function Link({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <a
      href={href(to)}
      onClick={(event) => {
        event.preventDefault();
        navigate(to);
      }}
    >
      {children}
    </a>
  );
}

export function ReportPage({ sessionId }: { sessionId: string }) {
  const { query } = useLocation();
  const revision = query.get("revision");
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    setReport(null);
    api<Report>("GET", `/v1/sessions/${sessionId}/report${revision ? `?revision=${revision}` : ""}`).then(setReport, setError);
  }, [sessionId, revision]);

  return (
    <section className="panel" aria-labelledby="report-title">
      <h1 id="report-title">리포트</h1>
      <ErrorNotice error={error} />
      {report && (
        <>
          <p>{safeText(report.summary, 500)}</p>
          <p>
            revision {report.revision} · 정책 {safeText(report.policyVersion, 40)} · {new Date(report.createdAt).toLocaleString()}
          </p>
          {report.changeReason && <p className="notice notice-info">{safeText(report.changeReason, 300)}</p>}
          {report.revision > 1 && (
            <nav aria-label="이전 revision">
              {Array.from({ length: report.revision - 1 }, (_, index) => index + 1).map((value) => (
                <Link key={value} to={`/sessions/${sessionId}/report?revision=${value}`}>
                  revision {value} 보기
                </Link>
              ))}
            </nav>
          )}
          <h2>도움 수준</h2>
          <p data-testid="exposure">{EXPOSURE[report.exposure] ?? report.exposure}</p>
          <h2>평가 차원</h2>
          <div className="table-wrap" tabIndex={0} aria-label="평가 차원 표">
            <table>
              <thead>
                <tr>
                  <th scope="col">차원</th>
                  <th scope="col">결과</th>
                  <th scope="col">점수</th>
                  <th scope="col">근거 종류</th>
                  <th scope="col">근거</th>
                </tr>
              </thead>
              <tbody>
                {report.dimensions.map((dimension) => (
                  <tr key={dimension.key} data-dimension={dimension.key}>
                    <th scope="row">{DIMENSIONS[dimension.key] ?? safeText(dimension.key, 40)}</th>
                    <td>{STATUS[dimension.status] ?? dimension.status}</td>
                    <td>{dimension.score === undefined ? "–" : dimension.score.toFixed(0)}</td>
                    <td>{REPRESENTATION[dimension.representation] ?? dimension.representation}</td>
                    <td>
                      {dimension.evidenceIds.length === 0
                        ? "없음"
                        : dimension.evidenceIds.map((id, index) => (
                            <Link key={id} to={`/sessions/${sessionId}/replay?evidence=${id}`}>
                              근거 {index + 1}
                            </Link>
                          ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h2>판정 범위</h2>
          {report.scopeLimitations.length === 0 ? <p>제한 사항이 없습니다.</p> : (
            <ul aria-label="판정 범위와 제한">
              {report.scopeLimitations.map((item, index) => (
                <li key={index}>{safeText(item, 300)}</li>
              ))}
            </ul>
          )}
          <h2>다음 추천</h2>
          {report.recommendations.length === 0 ? <p>지금 추천할 사건이 없습니다.</p> : (
            <ol aria-label="추천 사건">
              {report.recommendations.map((item) => (
                <li key={item.scenarioVersionId} data-recommendation={item.scenarioVersionId}>
                  <Link to={`/scenarios/${item.scenarioId}`}>{safeText(item.title, 200)}</Link> · 점수 {(item.scoreBps / 100).toFixed(0)}
                  <ul aria-label="추천 이유">
                    {item.reasons.map((reason) => (
                      <li key={reason}>{REASONS[reason] ?? reason}</li>
                    ))}
                  </ul>
                  <p className="note">
                    근거 부족 {(item.terms.evidenceGap ?? 0) / 100}% · 낮은 독립 성공 {(item.terms.lowIndependentSuccess ?? 0) / 100}% · 새 계열 {(item.terms.novelty ?? 0) / 100}% ·
                    선호 {(item.terms.preference ?? 0) / 100}%
                  </p>
                </li>
              ))}
            </ol>
          )}
          <p>
            <Link to={`/sessions/${sessionId}/replay`}>Replay로 근거 따라가기</Link> · <Link to="/skills">스킬 프로파일</Link>
          </p>
        </>
      )}
    </section>
  );
}
