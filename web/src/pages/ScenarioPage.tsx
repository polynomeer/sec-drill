import { useEffect, useState } from "react";
import { api, type ScenarioDetail, type Session } from "../api/client";
import { ErrorNotice } from "../components/Messages";
import { navigate, useLocation } from "../router";
import { safeText } from "../text";

const ENTRY = ["CTF", "WARGAME", "PURPLE"] as const;

export function ScenarioPage({ scenarioId }: { scenarioId: string }) {
  const { query } = useLocation();
  const parent = query.get("parent");
  const [detail, setDetail] = useState<ScenarioDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<ScenarioDetail>("GET", `/v1/scenarios/${scenarioId}`).then(setDetail, setError);
  }, [scenarioId]);

  async function start(mode: string) {
    if (!detail) return;
    setBusy(true);
    setError(null);
    try {
      // CTF → Purple makes a new Session linked to the original one; the original is never changed (prompt 11).
      const body = parent ? { scenarioVersionId: detail.scenarioVersionId, mode, parentSessionId: parent } : { scenarioVersionId: detail.scenarioVersionId, mode };
      const session = await api<Session>("POST", "/v1/sessions", body, { idempotent: true });
      navigate(`/sessions/${session.id}`);
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="scenario-title" className="panel">
      <ErrorNotice error={error} />
      {detail && (
        <>
          <h1 id="scenario-title">{safeText(detail.title, 200)}</h1>
          <p>{safeText(detail.brief, 4000)}</p>
          <dl>
            <dt>허용 대상</dt>
            <dd>{detail.allowedTargets.map((target) => safeText(target, 200)).join(", ")}</dd>
            <dt>예상 시간</dt>
            <dd>{detail.estimatedMinutes}분</dd>
            <dt>목표</dt>
            <dd>
              <ul>
                {detail.challenges.map((challenge) => (
                  <li key={challenge.id}>{safeText(challenge.objective, 500)}</li>
                ))}
              </ul>
            </dd>
          </dl>
          {parent && <p className="note">이전 Session과 연결된 새 Session으로 시작합니다. 이전 Session은 바뀌지 않습니다.</p>}
          <div className="actions-row">
            {ENTRY.filter((mode) => detail.modes.includes(mode)).map((mode) => (
              <button key={mode} type="button" disabled={busy} onClick={() => start(mode)}>
                {mode}로 시작
              </button>
            ))}
          </div>
        </>
      )}
    </section>
  );
}
