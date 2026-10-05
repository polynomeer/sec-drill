// Minimal typed client for the public API (15). Types come from the OpenAPI contract (src/api/schema.ts).
import type { components } from "./schema";

export type Schemas = components["schemas"];
export type Session = Schemas["Session"];
export type Lab = Schemas["Lab"];
export type ScenarioDetail = Schemas["ScenarioDetail"];
export type ScenarioPage = Schemas["ScenarioPage"];
export type Submission = Schemas["Submission"];
export type Evaluation = Schemas["Evaluation"];
export type Evidence = Schemas["Evidence"];
export type Hint = Schemas["Hint"];
export type ActionResult = Schemas["ActionResult"];
export type DetectionDataset = Schemas["DetectionDataset"];
export type ErrorEnvelope = Schemas["Error"];
export type Report = Schemas["Report"];
export type ReplayManifest = Schemas["ReplayManifest"];
export type ReplayChunk = Schemas["ReplayChunk"];
export type ReplayItem = Schemas["ReplayItem"];
export type ReplayState = Schemas["ReplayState"];
export type SkillPage = Schemas["SkillPage"];

/**
 * Two kinds of failure the learner must be told apart (07, prompt 11):
 * - "platform": the service could not do its job (5xx, 503, network). Work is kept; retrying later is safe.
 * - "request": the request was refused (4xx). The message says what to change.
 */
export class ApiError extends Error {
  constructor(
    readonly kind: "platform" | "request",
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: ErrorEnvelope["details"],
  ) {
    super(message);
  }
}

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|; )csrf_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : "";
}

export async function api<T>(method: string, path: string, body?: unknown, options: { idempotent?: boolean } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") headers["X-CSRF-Token"] = csrfToken();
  if (options.idempotent) headers["Idempotency-Key"] = crypto.randomUUID();
  let response: Response;
  try {
    response = await fetch(path, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError("platform", 0, "NETWORK", "서버에 연결하지 못했습니다.");
  }
  const text = await response.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!response.ok) {
    const envelope = (data ?? {}) as Partial<ErrorEnvelope>;
    const kind = response.status >= 500 || response.status === 0 ? "platform" : "request";
    throw new ApiError(kind, response.status, envelope.code ?? "UNKNOWN", envelope.message ?? `요청이 실패했습니다 (${response.status})`, envelope.details);
  }
  return data as T;
}

/**
 * Evidence stream over SSE, read with fetch so the client controls `Last-Event-ID` (EventSource cannot set it
 * on a fresh connection). The last seen seq is kept per Session in sessionStorage, so a reload or reconnect resumes
 * exactly after it (07: restore missed state after reconnecting).
 */
export function streamEvidence(sessionId: string, onEvidence: (item: Evidence) => void, onStatus: (status: "live" | "reconnecting") => void): () => void {
  const key = `secdrill.cursor.${sessionId}`;
  let stopped = false;
  let controller: AbortController | null = null;
  let delay = 1000;

  async function connect(): Promise<void> {
    while (!stopped) {
      controller = new AbortController();
      const cursor = sessionStorage.getItem(key);
      try {
        const response = await fetch(`/v1/sessions/${sessionId}/stream`, {
          headers: cursor ? { Accept: "text/event-stream", "Last-Event-ID": cursor } : { Accept: "text/event-stream" },
          credentials: "same-origin",
          signal: controller.signal,
        });
        if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);
        onStatus("live");
        delay = 1000;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let boundary: number;
          while ((boundary = buffer.indexOf("\n\n")) >= 0) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            let id: string | null = null;
            const data: string[] = [];
            for (const line of block.split("\n")) {
              if (line.startsWith("id:")) id = line.slice(3).trim();
              else if (line.startsWith("data:")) data.push(line.slice(5).trim());
            }
            if (data.length === 0) continue;
            try {
              onEvidence(JSON.parse(data.join("\n")) as Evidence);
              if (id) sessionStorage.setItem(key, id);
            } catch {
              // A malformed event is skipped; the cursor stays at the last good one.
            }
          }
        }
      } catch {
        if (stopped) return;
      }
      if (stopped) return;
      onStatus("reconnecting");
      await new Promise((resolve) => setTimeout(resolve, delay));
      delay = Math.min(delay * 2, 15000);
    }
  }
  void connect();
  return () => {
    stopped = true;
    controller?.abort();
  };
}
