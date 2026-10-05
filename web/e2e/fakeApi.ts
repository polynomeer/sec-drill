import type { Page, Route } from "@playwright/test";
import type { components } from "../src/api/schema";

// Contract-shaped fake of the public API for UI tests. Types come from the OpenAPI contract, so a response that
// drifts from it fails to compile. Server behaviour itself is tested in the Kotlin integration tests.
type S = components["schemas"];

export const ids = {
  scenario: "10000000-0000-4000-8000-0000000000a1",
  version: "10000000-0000-4000-8000-0000000000a2",
  challenge: "10000000-0000-4000-8000-0000000000a3",
  session: "10000000-0000-4000-8000-0000000000a4",
  lab: "10000000-0000-4000-8000-0000000000a5",
  submission: "10000000-0000-4000-8000-0000000000a6",
  purple: "10000000-0000-4000-8000-0000000000a7",
};

export const GATEWAY = "http://127.0.0.1:4999";

export class FakeApi {
  labState: S["Lab"]["state"] = "READY";
  evaluation: S["Evaluation"] | null = null;
  submitStatus = 202;
  finishMissing: string[] = ["objective_confirmed"];
  evidence: S["Evidence"][] = [];
  streamRequests: (string | null)[] = [];
  createdSessions: Record<string, unknown>[] = [];
  streamBatches: number[][] = [];

  readonly detail: S["ScenarioDetail"] = {
    id: ids.scenario, scenarioVersionId: ids.version, title: "Synthetic tenant order leak", brief: "합성 주문 서비스에서 tenant 경계 위반을 확인하세요.",
    modes: ["CTF", "PURPLE"], competencyTags: ["AUTHORIZATION"], allowedTargets: ["app.lab.internal"], estimatedMinutes: 30,
    challenges: [{ id: ids.challenge, objective: "다른 tenant의 주문을 읽을 수 있음을 증명하세요.", kind: "FLAG" }],
    patchPaths: ["app/orders.py", "app/authz.py"], actions: ["REVOKE_TOKEN", "ENABLE_AUDIT"],
    completionRequirements: { CTF: ["objective_confirmed"], PURPLE: ["objective_confirmed", "detection_evaluated", "action_applied", "patch_verified", "postmortem_submitted"] },
  };

  session(id = ids.session, mode: S["Session"]["mode"] = "CTF"): S["Session"] {
    return {
      id, scenarioId: ids.scenario, scenarioVersionId: ids.version, mode, status: "ACTIVE", phase: "ATTACK", version: 3, createdAt: "2026-10-05T00:00:00Z",
      lab: { id: ids.lab, sessionId: id, generation: 1, state: this.labState, expiresAt: "2026-10-05T01:00:00Z", isolationVerified: false },
    };
  }

  evidenceItem(seq: number, type: string, summary: Record<string, unknown>, trustLevel: S["Evidence"]["trustLevel"] = "SERVER_VERIFIED"): S["Evidence"] {
    return {
      id: `20000000-0000-4000-8000-${String(seq).padStart(12, "0")}`, sessionId: ids.session, seq, type, trustLevel,
      occurredAt: "2026-10-05T00:00:00Z", ingestedAt: "2026-10-05T00:00:00Z", payloadDigest: "a".repeat(64), hash: "b".repeat(64), summary,
    } as S["Evidence"];
  }

  async install(page: Page): Promise<void> {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    const error = (code: string, status: number, message: string, details?: unknown) => ({ code, message, requestId: "r", retryable: status >= 500, details });
    await page.route("**/v1/**", async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname;
      const method = request.method();
      if (path === "/v1/auth/session") return json(route, 200, { userId: ids.session, accessExpiresAt: "2026-10-05T01:00:00Z" });
      if (path === "/v1/scenarios") return json(route, 200, { items: [{ id: ids.scenario, scenarioVersionId: ids.version, title: this.detail.title, difficulty: "BEGINNER", modes: this.detail.modes, competencyTags: ["AUTHORIZATION"], estimatedMinutes: 30 }], nextCursor: null } satisfies S["ScenarioPage"]);
      if (path === `/v1/scenarios/${ids.scenario}`) return json(route, 200, this.detail);
      if (path === "/v1/sessions" && method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        this.createdSessions.push(body);
        return json(route, 201, this.session(body.mode === "PURPLE" ? ids.purple : ids.session, body.mode as S["Session"]["mode"]));
      }
      const sessionMatch = path.match(/^\/v1\/sessions\/([0-9a-f-]{36})(\/.*)?$/);
      if (sessionMatch) {
        const [, id, rest = ""] = sessionMatch;
        const mode = id === ids.purple ? "PURPLE" : "CTF";
        if (rest === "" && method === "GET") return json(route, 200, this.session(id, mode));
        if (rest === "/stream") {
          this.streamRequests.push(request.headers()["last-event-id"] ?? null);
          const batch = this.streamBatches.shift() ?? [];
          const body = this.evidence.filter((item) => batch.includes(item.seq)).map((item) => `id: ${item.seq}\nevent: evidence\ndata: ${JSON.stringify(item)}\n\n`).join("");
          return route.fulfill({ status: 200, contentType: "text/event-stream", body });
        }
        if (rest === `/labs/${ids.lab}/connect`) return json(route, 200, { connectUrl: `${GATEWAY}/connect?token=synthetic.token`, expiresAt: "2026-10-05T00:01:00Z" });
        if (rest === "/submissions") {
          if (this.submitStatus === 503) return json(route, 503, error("SERVICE_UNAVAILABLE", 503, "Service unavailable"));
          if (this.submitStatus === 422) return json(route, 422, error("VALIDATION_FAILED", 422, "Submission is invalid", { fieldErrors: [{ field: "content.flag", message: "must be 1-256 characters" }] }));
          return json(route, 202, { id: ids.submission, sessionId: id, kind: "FLAG", status: "ACCEPTED", createdAt: "2026-10-05T00:00:00Z" } satisfies S["Submission"]);
        }
        if (rest === "/finish") return json(route, 409, error("MISSING_GATES", 409, "Completion requirements are not met", { missingGates: this.finishMissing }));
        if (rest === "/detection-dataset") return json(route, 200, { variant: "TRAINING", generator: "tenant-orders-logs/1", representation: "SIMULATED", events: [] } satisfies S["DetectionDataset"]);
      }
      if (path === `/v1/submissions/${ids.submission}`) {
        const base = { id: ids.submission, sessionId: ids.session, kind: "FLAG" as const, createdAt: "2026-10-05T00:00:00Z" };
        return json(route, 200, this.evaluation ? { ...base, status: "EVALUATED", evaluation: this.evaluation } : { ...base, status: "EVALUATING" });
      }
      return json(route, 404, error("NOT_FOUND", 404, "Resource not found"));
    });
  }
}
