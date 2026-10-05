// Minimal CTF flow for T07 (prompt 08): login, scenario, Session, Lab, flag, result, finish.
// Talks only to the public API. The Lab opens on the Lab Gateway's own origin; no platform cookie goes there.
"use strict";

const $ = (id) => document.getElementById(id);
const state = { scenario: null, session: null, submission: null };

function csrf() {
  const match = document.cookie.match(/(?:^|; )csrf_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : "";
}

async function api(method, path, body, idempotent) {
  const headers = { "Content-Type": "application/json" };
  if (method !== "GET") headers["X-CSRF-Token"] = csrf();
  if (idempotent) headers["Idempotency-Key"] = crypto.randomUUID();
  const response = await fetch(path, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error((data && data.message) || `요청 실패 (${response.status})`);
  return data;
}

function show(section) {
  for (const id of ["login", "catalog", "scenario", "session"]) $(id).hidden = id !== section && !(section === "session" && id === "scenario");
}

function report(error) { $("error").textContent = error ? error.message : ""; }

async function boot() {
  try {
    await api("GET", "/v1/auth/session");
    await loadCatalog();
  } catch (error) {
    show("login");
  }
}

async function loadCatalog() {
  const page = await api("GET", "/v1/scenarios?mode=CTF");
  const list = $("scenarios");
  list.replaceChildren();
  for (const item of page.items) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${item.title} · ${item.difficulty} · ${item.estimatedMinutes}분`;
    button.addEventListener("click", () => openScenario(item.id).catch(report));
    const li = document.createElement("li");
    li.append(button);
    list.append(li);
  }
  if (page.items.length === 0) list.textContent = "출판된 CTF 사건이 없습니다.";
  show("catalog");
}

async function openScenario(id) {
  state.scenario = await api("GET", `/v1/scenarios/${id}`);
  $("scenario-title").textContent = state.scenario.title;
  $("scenario-brief").textContent = state.scenario.brief;
  $("scenario-targets").textContent = state.scenario.allowedTargets.join(", ");
  const select = $("challenge");
  select.replaceChildren(...state.scenario.challenges.filter((c) => c.kind === "FLAG").map((c) => new Option(c.objective, c.id)));
  show("scenario");
}

function renderSession(session) {
  state.session = session;
  $("session-status").textContent = session.status;
  const lab = session.lab;
  $("lab-status").textContent = lab ? `${lab.state} (generation ${lab.generation}, 만료 ${lab.expiresAt})` : "요청 전";
  $("open-lab").disabled = !lab || lab.state !== "READY";
  $("demo-banner").hidden = !lab || lab.isolationVerified;
}

async function refreshSession() {
  renderSession(await api("GET", `/v1/sessions/${state.session.id}`));
}

async function poll(check, attempts = 60) {
  for (let i = 0; i < attempts; i += 1) {
    if (await check()) return true;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  return false;
}

$("dev-login").addEventListener("click", async () => {
  try {
    await api("POST", "/v1/auth/dev-login", {});
    await loadCatalog();
  } catch (error) { report(error); }
});

$("start").addEventListener("click", async () => {
  try {
    renderSession(await api("POST", "/v1/sessions", { scenarioVersionId: state.scenario.scenarioVersionId, mode: "CTF" }, true));
    show("session");
  } catch (error) { report(error); }
});

$("request-lab").addEventListener("click", async () => {
  try {
    await api("POST", `/v1/sessions/${state.session.id}/labs`, { expectedVersion: state.session.version }, true);
    await poll(async () => { await refreshSession(); return state.session.lab && state.session.lab.state !== "REQUESTED" && state.session.lab.state !== "PROVISIONING"; });
  } catch (error) { report(error); }
});

$("open-lab").addEventListener("click", async () => {
  try {
    const connect = await api("POST", `/v1/sessions/${state.session.id}/labs/${state.session.lab.id}/connect`);
    window.open(connect.connectUrl, "_blank", "noopener,noreferrer");
  } catch (error) { report(error); }
});

$("flag-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await refreshSession();
    const content = { challengeId: $("challenge").value, flag: $("flag").value };
    $("flag").value = "";
    const submission = await api("POST", `/v1/sessions/${state.session.id}/submissions`, { kind: "FLAG", expectedVersion: state.session.version, content }, true);
    $("result").textContent = "채점 중…";
    await poll(async () => {
      const view = await api("GET", `/v1/submissions/${submission.id}`);
      if (!view.evaluation) return false;
      renderResult(view.evaluation);
      return true;
    });
    await refreshSession();
  } catch (error) { report(error); }
});

function renderResult(evaluation) {
  const labels = { PASS: "목표 확인됨", FAIL: "플래그가 맞지 않습니다", SYSTEM_ERROR: "판정 보류(플랫폼에서 목표를 확인하지 못함)" };
  const box = $("result");
  box.replaceChildren();
  const verdict = document.createElement("p");
  verdict.className = "verdict";
  verdict.textContent = `${labels[evaluation.verdict] || evaluation.verdict}${evaluation.demo ? " — 데모 결과(격리 미검증)" : ""}`;
  const gates = document.createElement("p");
  gates.textContent = evaluation.gates.map((g) => `${g.key}: ${g.result}`).join(", ");
  box.append(verdict, gates);
}

$("finish").addEventListener("click", async () => {
  try {
    renderSession(await api("POST", `/v1/sessions/${state.session.id}/finish`, { expectedVersion: state.session.version }));
  } catch (error) { report(error); }
});

$("stop").addEventListener("click", async () => {
  try {
    renderSession(await api("POST", `/v1/sessions/${state.session.id}/stop`, { expectedVersion: state.session.version }));
  } catch (error) { report(error); }
});

boot();
