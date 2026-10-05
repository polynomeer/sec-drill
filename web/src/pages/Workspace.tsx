import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import {
  api,
  streamEvidence,
  type ActionResult,
  type DetectionDataset,
  type Evidence,
  type Hint,
  type ScenarioDetail,
  type Session,
  type Submission,
} from "../api/client";
import { useAnnounce } from "../announce";
import { ErrorNotice, EvaluationSummary, label } from "../components/Messages";
import { href, navigate } from "../router";
import { safeText } from "../text";

type Tab = "app" | "terminal" | "code" | "logs";
const TABS: { id: Tab; title: string }[] = [
  { id: "app", title: "앱" },
  { id: "terminal", title: "터미널" },
  { id: "code", title: "코드" },
  { id: "logs", title: "로그" },
];

/** Per-browser storage for things the server does not keep yet (notes) or does not need (UI cursors). */
function useStored<T>(key: string, initial: T, storage: Storage = localStorage): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = storage.getItem(key);
      return raw ? (JSON.parse(raw) as T) : initial;
    } catch {
      return initial;
    }
  });
  const set = useCallback(
    (next: T) => {
      setValue(next);
      try {
        storage.setItem(key, JSON.stringify(next));
      } catch {
        // Storage may be unavailable (private mode); the value lives for this page only.
      }
    },
    [key, storage],
  );
  return [value, set];
}

export function Workspace({ sessionId }: { sessionId: string }) {
  const announce = useAnnounce();
  const [session, setSession] = useState<Session | null>(null);
  const [detail, setDetail] = useState<ScenarioDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [tab, setTab] = useStored<Tab>(`secdrill.tab.${sessionId}`, "app", sessionStorage);
  const previousLab = useRef<string | undefined>(undefined);
  const submissions = useSubmissions(sessionId);

  const refresh = useCallback(async () => {
    try {
      const next = await api<Session>("GET", `/v1/sessions/${sessionId}`);
      setSession(next);
      setError(null);
      const labState = next.lab?.state;
      if (labState && labState !== previousLab.current) {
        if (previousLab.current !== undefined) announce(`Lab 상태: ${labState}`);
        previousLab.current = labState;
      }
      return next;
    } catch (failure) {
      setError(failure);
      return null;
    }
  }, [sessionId, announce]);

  useEffect(() => {
    void refresh().then((next) => {
      if (next) api<ScenarioDetail>("GET", `/v1/scenarios/${next.scenarioId}?versionId=${next.scenarioVersionId}`).then(setDetail, setError);
    });
  }, [refresh]);

  // Poll only while something is changing on the server; the stream carries the rest.
  useEffect(() => {
    const state = session?.lab?.state;
    if (state !== "REQUESTED" && state !== "PROVISIONING" && state !== "TERMINATING") return;
    const timer = setInterval(() => void refresh(), 2000);
    return () => clearInterval(timer);
  }, [session?.lab?.state, refresh]);

  if (!session) {
    return (
      <section className="panel">
        <ErrorNotice error={error} onRetry={() => void refresh()} />
        {!error && <p>불러오는 중…</p>}
      </section>
    );
  }

  return (
    <div className="workspace" data-testid="workspace">
      <header className="workspace-summary">
        <h1>{detail ? safeText(detail.title, 200) : "작업 공간"}</h1>
        <p>
          {session.mode} · 상태 {session.status} · 단계 {session.phase}
          {session.lab && !session.lab.isolationVerified && <span className="badge-demo"> · 데모(격리 미검증)</span>}
        </p>
      </header>
      <ErrorNotice error={error} onRetry={() => void refresh()} />
      <aside className="workspace-left" aria-label="목표와 메모">
        <Objectives session={session} detail={detail} />
        <Notes sessionId={sessionId} />
      </aside>
      <main className="workspace-main">
        <Tabs tab={tab} onChange={setTab} />
        <TabPanel id="app" active={tab}>
          <LabPanel session={session} refresh={refresh} />
        </TabPanel>
        <TabPanel id="terminal" active={tab}>
          <p>이 사건의 터미널은 아직 제공되지 않습니다. 앱 탭에서 Lab을 별도 창으로 열어 조사하세요.</p>
        </TabPanel>
        <TabPanel id="code" active={tab}>
          <PatchPanel session={session} detail={detail} refresh={refresh} submissions={submissions} />
        </TabPanel>
        <TabPanel id="logs" active={tab}>
          <DetectionPanel session={session} refresh={refresh} submissions={submissions} />
          <EvidenceTimeline sessionId={sessionId} />
        </TabPanel>
      </main>
      <aside className="workspace-right" aria-label="제출과 진행">
        <FlagPanel session={session} detail={detail} refresh={refresh} submissions={submissions} />
        {session.mode === "PURPLE" && detail && detail.actions.length > 0 && <ActionsPanel session={session} detail={detail} refresh={refresh} />}
        <FinishPanel session={session} detail={detail} refresh={refresh} />
      </aside>
    </div>
  );
}

function Tabs({ tab, onChange }: { tab: Tab; onChange: (tab: Tab) => void }) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  function onKey(event: KeyboardEvent<HTMLDivElement>) {
    const index = TABS.findIndex((item) => item.id === tab);
    const next = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: TABS.length - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    const target = TABS[(next + TABS.length) % TABS.length];
    onChange(target.id);
    refs.current[target.id]?.focus();
  }
  return (
    <div role="tablist" aria-label="작업 탭" className="tabs" onKeyDown={onKey}>
      {TABS.map((item) => (
        <button
          key={item.id}
          ref={(element) => {
            refs.current[item.id] = element;
          }}
          role="tab"
          type="button"
          id={`tab-${item.id}`}
          aria-selected={tab === item.id}
          aria-controls={`panel-${item.id}`}
          tabIndex={tab === item.id ? 0 : -1}
          onClick={() => onChange(item.id)}
        >
          {item.title}
        </button>
      ))}
    </div>
  );
}

function TabPanel({ id, active, children }: { id: Tab; active: Tab; children: ReactNode }) {
  return (
    <div role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`} hidden={active !== id} className="tabpanel" tabIndex={0}>
      {children}
    </div>
  );
}

function Objectives({ session, detail }: { session: Session; detail: ScenarioDetail | null }) {
  const [hints, setHints] = useStored<Hint[]>(`secdrill.hints.${session.id}`, [], sessionStorage);
  const [error, setError] = useState<unknown>(null);
  const announce = useAnnounce();
  if (!detail) return null;
  async function nextHint(challengeId: string) {
    const level = Math.min(4, hints.filter((hint) => hint.challengeId === challengeId).length + 1);
    try {
      const hint = await api<Hint>("POST", `/v1/sessions/${session.id}/hints`, { challengeId, level });
      setHints([...hints.filter((item) => !(item.challengeId === challengeId && item.level === hint.level)), hint]);
      setError(null);
      announce(`힌트 H${hint.level}을 받았습니다. 누적 감점 ${hint.totalPointDeduction}점`);
    } catch (failure) {
      setError(failure);
    }
  }
  return (
    <section aria-labelledby="objectives-title">
      <h2 id="objectives-title">목표</h2>
      <ul>
        {detail.challenges.map((challenge) => {
          const received = hints.filter((hint) => hint.challengeId === challenge.id).sort((a, b) => a.level - b.level);
          return (
            <li key={challenge.id}>
              <p>{safeText(challenge.objective, 500)}</p>
              <button type="button" onClick={() => void nextHint(challenge.id)} disabled={received.length >= 4}>
                다음 힌트(H{received.length + 1}) 받기
              </button>
              {received.length > 0 && (
                <ol className="hints" aria-label="받은 힌트">
                  {received.map((hint) => (
                    <li key={hint.level}>
                      H{hint.level}: {safeText(hint.text, 2000)}
                    </li>
                  ))}
                </ol>
              )}
              {received.length > 0 && <p className="note">누적 감점 {received[received.length - 1].totalPointDeduction}점. H3 이상은 도움받은 성공으로 기록됩니다.</p>}
            </li>
          );
        })}
      </ul>
      <ErrorNotice error={error} />
    </section>
  );
}

function Notes({ sessionId }: { sessionId: string }) {
  const [notes, setNotes] = useStored(`secdrill.notes.${sessionId}`, { hypothesis: "", memo: "" });
  return (
    <section aria-labelledby="notes-title">
      <h2 id="notes-title">가설과 메모</h2>
      <p className="note">이 브라우저에만 저장됩니다. 서버로 보내지 않으며 Lab이 만료되어도 남습니다.</p>
      <label>
        가설
        <textarea value={notes.hypothesis} onChange={(event) => setNotes({ ...notes, hypothesis: event.target.value })} rows={3} />
      </label>
      <label>
        메모
        <textarea value={notes.memo} onChange={(event) => setNotes({ ...notes, memo: event.target.value })} rows={5} />
      </label>
    </section>
  );
}

function LabPanel({ session, refresh }: { session: Session; refresh: () => Promise<Session | null> }) {
  const [error, setError] = useState<unknown>(null);
  const lab = session.lab;
  const ended = !lab || ["TERMINATED", "FAILED", "CLEANUP_FAILED"].includes(lab.state);
  const active = session.status === "ACTIVE" || session.status === "CREATED";
  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
    } catch (failure) {
      setError(failure);
    }
    await refresh();
  }
  return (
    <section aria-labelledby="lab-title">
      <h2 id="lab-title">Lab</h2>
      {lab ? (
        <p data-testid="lab-state">
          상태 {lab.state} · generation {lab.generation} · 만료 {new Date(lab.expiresAt).toLocaleString()}
        </p>
      ) : (
        <p data-testid="lab-state">아직 Lab이 없습니다.</p>
      )}
      {lab && ended && active && (
        <p className="notice notice-info" role="note">
          Lab이 종료되었습니다. 메모와 이미 접수된 제출은 보존되고, Lab 안의 임시 파일은 사라졌을 수 있습니다. 새 Lab을 요청하면 새 generation이 만들어집니다.
        </p>
      )}
      {lab && !lab.isolationVerified && <p className="demo">이 Lab은 격리가 검증되지 않은 개발 환경입니다. 결과는 데모로 표시됩니다.</p>}
      <div className="actions-row">
        {active && ended && (
          <button type="button" onClick={() => run(() => api("POST", `/v1/sessions/${session.id}/labs`, { expectedVersion: session.version }, { idempotent: true }))}>
            {lab ? "새 Lab 요청" : "Lab 준비"}
          </button>
        )}
        {lab?.state === "READY" && (
          <button
            type="button"
            onClick={() =>
              run(async () => {
                const connect = await api<{ connectUrl: string }>("POST", `/v1/sessions/${session.id}/labs/${lab.id}/connect`);
                // The Lab lives on the gateway's own origin: a new window without opener, never an embedded frame.
                window.open(connect.connectUrl, "_blank", "noopener,noreferrer");
              })
            }
          >
            Lab 열기(별도 origin)
          </button>
        )}
        {active && (
          <button
            type="button"
            className="danger"
            onClick={() => {
              if (window.confirm("Session을 취소하고 Lab을 종료할까요? 늦게 만들어진 자원도 회수됩니다.")) {
                void run(() => api("POST", `/v1/sessions/${session.id}/stop`, { expectedVersion: session.version }));
              }
            }}
          >
            Session 취소
          </button>
        )}
      </div>
      <ErrorNotice error={error} />
    </section>
  );
}

type Submissions = { list: Submission[]; add: (submission: Submission) => void };

/** One list for the whole workspace, so panels never overwrite each other's tracked submissions. */
function useSubmissions(sessionId: string): Submissions {
  const [ids, setIds] = useStored<string[]>(`secdrill.submissions.${sessionId}`, [], sessionStorage);
  const [items, setItems] = useState<Record<string, Submission>>({});
  const announce = useAnnounce();
  useEffect(() => {
    let cancelled = false;
    async function poll() {
      for (const id of ids) {
        const current = items[id];
        if (current?.evaluation) continue;
        try {
          const next = await api<Submission>("GET", `/v1/submissions/${id}`);
          if (cancelled) return;
          setItems((previous) => ({ ...previous, [id]: next }));
          if (next.evaluation && !current?.evaluation) announce(`채점 결과: ${next.evaluation.verdict}`);
        } catch {
          // Keep polling; a transient error is shown by the next successful call.
        }
      }
    }
    void poll();
    const timer = setInterval(() => void poll(), 2000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [ids, items, announce]);
  const add = (submission: Submission) => {
    setIds([submission.id, ...ids.filter((id) => id !== submission.id)]);
    setItems((previous) => ({ ...previous, [submission.id]: submission }));
  };
  return { list: ids.map((id) => items[id]).filter(Boolean) as Submission[], add };
}

function SubmissionList({ submissions, kind }: { submissions: Submission[]; kind: string }) {
  const mine = submissions.filter((item) => item.kind === kind);
  if (mine.length === 0) return null;
  return (
    <ol className="submissions" aria-label="제출 이력">
      {mine.map((submission) => (
        <li key={submission.id}>
          <EvaluationSummary submission={submission} />
        </li>
      ))}
    </ol>
  );
}

function FlagPanel({ session, detail, refresh, submissions }: { session: Session; detail: ScenarioDetail | null; refresh: () => Promise<Session | null>; submissions: Submissions }) {
  const { list, add } = submissions;
  const [flag, setFlag] = useState("");
  const [challenge, setChallenge] = useState("");
  const [error, setError] = useState<unknown>(null);
  const flags = detail?.challenges.filter((item) => item.kind === "FLAG") ?? [];
  if (flags.length === 0 || session.mode === "WARGAME") return null;
  return (
    <section aria-labelledby="flag-title">
      <h2 id="flag-title">플래그 제출</h2>
      <form
        onSubmit={async (event) => {
          event.preventDefault();
          try {
            const latest = (await refresh()) ?? session;
            const submission = await api<Submission>(
              "POST",
              `/v1/sessions/${session.id}/submissions`,
              { kind: "FLAG", expectedVersion: latest.version, content: { challengeId: challenge || flags[0].id, flag } },
              { idempotent: true },
            );
            setFlag("");
            setError(null);
            add(submission);
            await refresh();
          } catch (failure) {
            setError(failure);
          }
        }}
      >
        <label>
          목표
          <select value={challenge || flags[0].id} onChange={(event) => setChallenge(event.target.value)}>
            {flags.map((item) => (
              <option key={item.id} value={item.id}>
                {safeText(item.objective, 120)}
              </option>
            ))}
          </select>
        </label>
        <label>
          플래그
          <input value={flag} onChange={(event) => setFlag(event.target.value)} autoComplete="off" maxLength={256} required />
        </label>
        <button type="submit">제출</button>
      </form>
      <ErrorNotice error={error} />
      <SubmissionList submissions={list} kind="FLAG" />
    </section>
  );
}

function PatchPanel({ session, detail, refresh, submissions }: { session: Session; detail: ScenarioDetail | null; refresh: () => Promise<Session | null>; submissions: Submissions }) {
  const { list, add } = submissions;
  const [files, setFiles] = useStored<Record<string, string>>(`secdrill.patch.${session.id}`, {});
  const [explanation, setExplanation] = useState("");
  const [error, setError] = useState<unknown>(null);
  const paths = detail?.patchPaths ?? [];
  if (paths.length === 0 || !["PURPLE", "PATCH"].includes(session.mode)) return <p>이 Session에는 패치 단계가 없습니다.</p>;
  return (
    <section aria-labelledby="patch-title">
      <h2 id="patch-title">패치</h2>
      <p className="note">바꿀 수 있는 파일만 표시됩니다. 파일 전체 내용을 붙여 넣으세요. 비워 둔 파일은 제출하지 않습니다.</p>
      {paths.map((path) => (
        <label key={path}>
          <code>{safeText(path, 200)}</code>
          <textarea className="code" spellCheck={false} rows={12} value={files[path] ?? ""} onChange={(event) => setFiles({ ...files, [path]: event.target.value })} />
        </label>
      ))}
      <label>
        수정 설명
        <textarea rows={3} value={explanation} onChange={(event) => setExplanation(event.target.value)} maxLength={8192} />
      </label>
      <button
        type="button"
        onClick={async () => {
          try {
            const latest = (await refresh()) ?? session;
            const changed = Object.fromEntries(Object.entries(files).filter(([path, text]) => paths.includes(path) && text.trim() !== ""));
            const submission = await api<Submission>(
              "POST",
              `/v1/sessions/${session.id}/submissions`,
              { kind: "PATCH", expectedVersion: latest.version, content: { files: changed, explanation } },
              { idempotent: true },
            );
            setError(null);
            add(submission);
          } catch (failure) {
            setError(failure);
          }
        }}
      >
        패치 제출
      </button>
      <ErrorNotice error={error} />
      <SubmissionList submissions={list} kind="PATCH" />
    </section>
  );
}

function DetectionPanel({ session, refresh, submissions }: { session: Session; refresh: () => Promise<Session | null>; submissions: Submissions }) {
  const { list, add } = submissions;
  const [dataset, setDataset] = useState<DetectionDataset | null>(null);
  const [rule, setRule] = useStored(`secdrill.rule.${session.id}`, '{"op":"neq","field":"tenantId","compareField":"resourceTenantId"}');
  const [explanation, setExplanation] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [filter, setFilter] = useState("");
  const supported = session.mode === "PURPLE" || session.mode === "DETECTION";
  useEffect(() => {
    if (supported) api<DetectionDataset>("GET", `/v1/sessions/${session.id}/detection-dataset`).then(setDataset, setError);
  }, [session.id, supported]);
  const shown = useMemo(
    () => (dataset?.events ?? []).filter((event) => !filter || JSON.stringify(event).includes(filter)).slice(0, 200),
    [dataset, filter],
  );
  if (!supported) return null;
  return (
    <section aria-labelledby="detection-title">
      <h2 id="detection-title">탐지 규칙</h2>
      <p className="note">아래는 공개 training 합성 로그(SIMULATED)입니다. 채점은 이름·시간이 다른 숨은 holdout으로 합니다.</p>
      <label>
        로그 검색
        <input value={filter} onChange={(event) => setFilter(event.target.value)} />
      </label>
      <div className="table-wrap" tabIndex={0} aria-label="training 로그">
        <table>
          <thead>
            <tr>
              <th scope="col">time</th>
              <th scope="col">actorId</th>
              <th scope="col">tenantId</th>
              <th scope="col">resourceTenantId</th>
              <th scope="col">status</th>
              <th scope="col">routeGroup</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((event) => (
              <tr key={event.eventId}>
                <td>{event.time}</td>
                <td>{safeText(event.actorId, 80)}</td>
                <td>{safeText(event.tenantId, 80)}</td>
                <td>{safeText(event.resourceTenantId, 80)}</td>
                <td>{event.status ?? ""}</td>
                <td>{safeText(event.routeGroup, 80)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <label>
        규칙(JSON)
        <textarea className="code" spellCheck={false} rows={6} value={rule} onChange={(event) => setRule(event.target.value)} />
      </label>
      <label>
        설명
        <textarea rows={2} value={explanation} onChange={(event) => setExplanation(event.target.value)} maxLength={8192} />
      </label>
      <button
        type="button"
        onClick={async () => {
          let parsed: unknown;
          try {
            parsed = JSON.parse(rule);
          } catch {
            setError(new Error("규칙이 올바른 JSON이 아닙니다."));
            return;
          }
          try {
            const latest = (await refresh()) ?? session;
            const submission = await api<Submission>(
              "POST",
              `/v1/sessions/${session.id}/submissions`,
              { kind: "DETECTION", expectedVersion: latest.version, content: { rule: parsed, explanation } },
              { idempotent: true },
            );
            setError(null);
            add(submission);
          } catch (failure) {
            setError(failure);
          }
        }}
      >
        규칙 제출
      </button>
      {error instanceof Error && !("kind" in error) ? <p role="alert">{error.message}</p> : <ErrorNotice error={error} />}
      <SubmissionList submissions={list} kind="DETECTION" />
    </section>
  );
}

const TRUST_LABELS: Record<string, string> = {
  SERVER_VERIFIED: "서버 확인",
  OBSERVED: "관측",
  SIMULATED: "모델(SIMULATED)",
  USER_REPORTED: "사용자 보고",
};

function EvidenceTimeline({ sessionId }: { sessionId: string }) {
  const [items, setItems] = useState<Evidence[]>([]);
  const [status, setStatus] = useState<"live" | "reconnecting">("reconnecting");
  useEffect(() => {
    setItems([]);
    return streamEvidence(
      sessionId,
      (item) => setItems((previous) => (previous.some((existing) => existing.seq === item.seq) ? previous : [...previous, item].sort((a, b) => a.seq - b.seq))),
      setStatus,
    );
  }, [sessionId]);
  return (
    <section aria-labelledby="timeline-title">
      <h2 id="timeline-title">근거 타임라인</h2>
      <p data-testid="stream-status">{status === "live" ? "실시간 연결됨" : "다시 연결하는 중… 연결되면 놓친 항목부터 이어서 받습니다."}</p>
      <ol className="timeline" aria-label="근거 목록">
        {items.map((item) => (
          <li key={item.seq} data-seq={item.seq}>
            <span className="seq">#{item.seq}</span> {safeText(item.type, 80)} <span className={`trust trust-${item.trustLevel}`}>[{TRUST_LABELS[item.trustLevel] ?? item.trustLevel}]</span>
            <code className="summary">{safeText(item.summary, 400)}</code>
          </li>
        ))}
      </ol>
    </section>
  );
}

const ACTION_PARAMETERS: Record<string, string> = { REVOKE_TOKEN: "tokenId", DISABLE_ENDPOINT: "routeGroup", ISOLATE_WORKLOAD: "workloadId", ENABLE_AUDIT: "source" };

function ActionsPanel({ session, detail, refresh }: { session: Session; detail: ScenarioDetail; refresh: () => Promise<Session | null> }) {
  const [type, setType] = useState(detail.actions[0]);
  const [target, setTarget] = useState("");
  const [result, setResult] = useState<ActionResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  return (
    <section aria-labelledby="actions-title">
      <h2 id="actions-title">대응 액션</h2>
      <p className="note">대응 모델에만 적용되는 SIMULATED 액션입니다. 실제 Lab이나 VM은 바뀌지 않습니다.</p>
      <form
        onSubmit={async (event) => {
          event.preventDefault();
          try {
            const latest = (await refresh()) ?? session;
            const next = await api<ActionResult>(
              "POST",
              `/v1/sessions/${session.id}/actions`,
              { type, parameters: { [ACTION_PARAMETERS[type]]: target }, expectedVersion: latest.version },
              { idempotent: true },
            );
            setResult(next);
            setError(null);
            await refresh();
          } catch (failure) {
            setError(failure);
          }
        }}
      >
        <label>
          액션
          <select value={type} onChange={(event) => setType(event.target.value as typeof type)}>
            {detail.actions.map((action) => (
              <option key={action} value={action}>
                {action}
              </option>
            ))}
          </select>
        </label>
        <label>
          대상({ACTION_PARAMETERS[type]})
          <input value={target} onChange={(event) => setTarget(event.target.value)} required maxLength={128} />
        </label>
        <button type="submit">적용(모델)</button>
      </form>
      {result && (
        <dl className="state" data-representation={result.representation}>
          <dt>tick</dt>
          <dd>{result.state.tick}</dd>
          <dt>유출된 합성 레코드</dt>
          <dd>{result.state.leakedSyntheticRecords}</dd>
          <dt>가용성</dt>
          <dd>{Math.round(result.state.availability * 100)}%</dd>
          <dt>정상 업무 성공률</dt>
          <dd>{Math.round(result.state.workloadSuccess * 100)}%</dd>
          <dt>증거 coverage</dt>
          <dd>{Math.round(result.state.evidenceCoverage * 100)}%</dd>
        </dl>
      )}
      <ErrorNotice error={error} />
    </section>
  );
}

function FinishPanel({ session, detail, refresh }: { session: Session; detail: ScenarioDetail | null; refresh: () => Promise<Session | null> }) {
  const [error, setError] = useState<unknown>(null);
  const announce = useAnnounce();
  const required = (detail?.completionRequirements as Record<string, string[] | undefined> | undefined)?.[session.mode] ?? [];
  const canContinue = detail?.modes.includes("PURPLE") && session.mode !== "PURPLE";
  return (
    <section aria-labelledby="finish-title">
      <h2 id="finish-title">완료</h2>
      {required.length > 0 && (
        <>
          <p>완료 조건(서버가 근거로 판정):</p>
          <ul>
            {required.map((gate) => (
              <li key={gate}>{label(gate)}</li>
            ))}
          </ul>
        </>
      )}
      {session.status === "ACTIVE" && (
        <button
          type="button"
          onClick={async () => {
            try {
              const latest = (await refresh()) ?? session;
              await api("POST", `/v1/sessions/${session.id}/finish`, { expectedVersion: latest.version });
              setError(null);
              announce("Session을 제출했습니다. Lab을 종료합니다.");
              await refresh();
            } catch (failure) {
              setError(failure);
            }
          }}
        >
          완료하고 Lab 종료
        </button>
      )}
      <ErrorNotice error={error} />
      {["SUBMITTED", "EVALUATING", "COMPLETED", "EVALUATION_FAILED"].includes(session.status) && (
        <p>
          <a
            href={href(`/sessions/${session.id}/report`)}
            onClick={(event) => {
              event.preventDefault();
              navigate(`/sessions/${session.id}/report`);
            }}
          >
            리포트 보기
          </a>
        </p>
      )}
      {canContinue && detail && (
        <p>
          <a
            href={href(`/scenarios/${session.scenarioId}?parent=${session.id}`)}
            onClick={(event) => {
              event.preventDefault();
              navigate(`/scenarios/${session.scenarioId}?parent=${session.id}`);
            }}
          >
            Purple로 이어가기(새 연결 Session)
          </a>
        </p>
      )}
    </section>
  );
}
