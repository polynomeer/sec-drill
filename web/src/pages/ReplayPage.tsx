import { useEffect, useMemo, useState } from "react";
import { api, type ReplayChunk, type ReplayItem, type ReplayManifest, type ReplayState } from "../api/client";
import { ARTIFACT, REPRESENTATION } from "../components/Labels";
import { ErrorNotice } from "../components/Messages";
import { useLocation } from "../router";
import { safeText } from "../text";

/**
 * Replay (22): recorded facts and the model are shown apart. The timeline lists ledger items with their trust level;
 * the model panel recomputes the IR state at a tick from the nearest checkpoint and says whether it matches the
 * digest recorded at that tick. Navigation works by list, search and previous/next buttons, not only a slider (07).
 */
export function ReplayPage({ sessionId }: { sessionId: string }) {
  const { query } = useLocation();
  const focus = query.get("evidence");
  const [manifest, setManifest] = useState<ReplayManifest | null>(null);
  const [items, setItems] = useState<ReplayItem[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [search, setSearch] = useState("");
  const [cursor, setCursor] = useState(0);
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<ReplayState | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const next = await api<ReplayManifest>("GET", `/v1/sessions/${sessionId}/replay`);
        setManifest(next);
        const chunks = await Promise.all(next.chunks.map((chunk) => api<ReplayChunk>("GET", chunk.downloadPath)));
        const all = chunks.flatMap((chunk) => chunk.items);
        setItems(all);
        if (focus) setCursor(Math.max(0, all.findIndex((item) => item.id === focus)));
        setTick(next.lastTick);
      } catch (failure) {
        setError(failure);
      }
    })();
  }, [sessionId, focus]);

  useEffect(() => {
    if (!manifest) return;
    api<ReplayState>("GET", `/v1/sessions/${sessionId}/replay/state?tick=${tick}`).then(setState, setError);
  }, [manifest, sessionId, tick]);

  const shown = useMemo(() => items.filter((item) => !search || `${item.type} ${JSON.stringify(item.summary)}`.includes(search)), [items, search]);
  const current = items[cursor];

  useEffect(() => {
    if (current) document.getElementById(`seq-${current.seq}`)?.scrollIntoView({ block: "nearest" });
  }, [current]);

  return (
    <section className="panel" aria-labelledby="replay-title">
      <h1 id="replay-title">Replay</h1>
      <ErrorNotice error={error} />
      {manifest && (
        <>
          <p>
            seq {manifest.firstSeq}–{manifest.lastSeq} · 대응 모델 {safeText(manifest.engineVersion, 40)} · tick 0–{manifest.lastTick}
          </p>
          {manifest.gaps.length > 0 && (
            <div className="notice notice-info" role="note">
              일부 근거는 원본이 없습니다:{" "}
              {manifest.gaps.map((gap) => `#${gap.fromSeq}${gap.toSeq !== gap.fromSeq ? `–${gap.toSeq}` : ""} (${gap.reason})`).join(", ")}. 해당 구간은 요약과 digest만 보여 줍니다.
            </div>
          )}
          <h2>기록된 근거</h2>
          <div className="actions-row">
            <button type="button" onClick={() => setCursor((value) => Math.max(0, value - 1))} disabled={cursor <= 0}>
              이전 항목
            </button>
            <button type="button" onClick={() => setCursor((value) => Math.min(items.length - 1, value + 1))} disabled={cursor >= items.length - 1}>
              다음 항목
            </button>
            <label>
              검색
              <input value={search} onChange={(event) => setSearch(event.target.value)} />
            </label>
          </div>
          {current && (
            <p data-testid="current-item" aria-live="polite">
              선택: #{current.seq} {safeText(current.type, 80)} [{REPRESENTATION[current.trustLevel] ?? current.trustLevel}]
            </p>
          )}
          <ol className="timeline" aria-label="근거 seq 목록">
            {shown.map((item) => (
              <li key={item.seq} id={`seq-${item.seq}`} data-seq={item.seq} data-evidence={item.id} aria-current={current?.seq === item.seq ? "true" : undefined}>
                <span className="seq">#{item.seq}</span> {safeText(item.type, 80)} <span>[{REPRESENTATION[item.trustLevel] ?? item.trustLevel}]</span>
                {item.artifact !== "NONE" && <span className="note"> · {ARTIFACT[item.artifact]}</span>}
                <code className="summary">{safeText(item.summary, 400)}</code>
              </li>
            ))}
          </ol>
          <h2>대응 모델 재계산</h2>
          <p className="note">모델 계산 결과이며 관측된 사실이 아닙니다. 가장 가까운 이전 checkpoint에서 다시 계산합니다.</p>
          <label>
            tick ({tick} / {manifest.lastTick})
            <input type="range" min={0} max={manifest.lastTick} value={tick} onChange={(event) => setTick(Number(event.target.value))} />
          </label>
          <div className="actions-row">
            <button type="button" onClick={() => setTick((value) => Math.max(0, value - 1))} disabled={tick <= 0}>
              이전 tick
            </button>
            <button type="button" onClick={() => setTick((value) => Math.min(manifest.lastTick, value + 1))} disabled={tick >= manifest.lastTick}>
              다음 tick
            </button>
          </div>
          {state && (
            <dl className="state" data-testid="model-state" data-representation={state.representation}>
              <dt>표현</dt>
              <dd>{REPRESENTATION[state.representation]}</dd>
              <dt>checkpoint</dt>
              <dd>tick {state.fromCheckpointTick}에서 재계산</dd>
              <dt>기록과 비교</dt>
              <dd data-testid="digest-match">
                {state.recordedDigest == null ? "기록 전(초기 상태)" : state.recordedDigest === state.stateDigest ? "기록된 상태와 일치" : "기록된 상태와 다름"}
              </dd>
              <dt>유출된 합성 레코드</dt>
              <dd>{state.state.leakedSyntheticRecords}</dd>
              <dt>가용성</dt>
              <dd>{Math.round(state.state.availability * 100)}%</dd>
              <dt>정상 업무 성공률</dt>
              <dd>{Math.round(state.state.workloadSuccess * 100)}%</dd>
              <dt>증거 coverage</dt>
              <dd>{Math.round(state.state.evidenceCoverage * 100)}%</dd>
            </dl>
          )}
        </>
      )}
    </section>
  );
}
