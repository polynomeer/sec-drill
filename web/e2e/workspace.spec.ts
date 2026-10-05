import { expect, test } from "@playwright/test";
import { FakeApi, GATEWAY, ids } from "./fakeApi";

const workspace = `/app/sessions/${ids.session}`;

test("keyboard only: catalog, scenario, start and tab between work areas", async ({ page }, info) => {
  test.skip(info.project.name === "phone", "keyboard flow is checked on desktop");
  const api = new FakeApi();
  await api.install(page);
  await page.goto("/app/scenarios");
  const link = page.getByRole("link", { name: "Synthetic tenant order leak" });
  await link.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Synthetic tenant order leak" })).toBeVisible();
  await page.getByRole("button", { name: "CTF로 시작" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("workspace")).toBeVisible();
  expect(api.createdSessions[0]).toEqual({ scenarioVersionId: ids.version, mode: "CTF" });

  const appTab = page.getByRole("tab", { name: "앱" });
  await appTab.focus();
  await page.keyboard.press("ArrowRight");
  await expect(page.getByRole("tab", { name: "터미널" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tab", { name: "터미널" })).toBeFocused();
  await page.keyboard.press("End");
  await expect(page.getByRole("tab", { name: "로그" })).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("ArrowRight");
  await expect(page.getByRole("tab", { name: "앱" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tabpanel")).toHaveCount(1);
});

test("small screens get one column with the objective summary kept in view and no sideways scrolling", async ({ page }, info) => {
  test.skip(info.project.name !== "phone", "phone viewport only");
  await new FakeApi().install(page);
  await page.goto(workspace);
  await expect(page.getByTestId("workspace")).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const main = await page.locator(".workspace-main").boundingBox();
  const left = await page.locator(".workspace-left").boundingBox();
  expect(left!.y).toBeGreaterThan(main!.y);
  await page.mouse.wheel(0, 2000);
  await expect(page.locator(".workspace-summary")).toBeInViewport();
});

test("Lab output is shown as text: no markup runs and terminal escapes are removed", async ({ page }) => {
  const api = new FakeApi();
  api.evidence = [api.evidenceItem(1, "OBJECTIVE_CONFIRMED", { note: '<img src=x onerror="window.__pwned=1">', ansi: "\u001b[31mred\u001b[0m" }, "OBSERVED")];
  api.streamBatches = [[1]];
  await api.install(page);
  await page.goto(workspace);
  await page.getByRole("tab", { name: "로그" }).click();
  const item = page.locator(".timeline li").first();
  await expect(item).toContainText("<img src=x");
  await expect(item).toContainText("red");
  await expect(item).not.toContainText("\u001b");
  await expect(page.locator(".timeline img")).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  await expect(item).toContainText("[관측]");
});

test("the Lab opens on the gateway's own origin in a new window, never inside the platform page", async ({ page }) => {
  await new FakeApi().install(page);
  await page.addInitScript(() => {
    (window as unknown as { __opened: unknown[] }).__opened = [];
    window.open = ((url: string, target: string, features: string) => {
      (window as unknown as { __opened: unknown[] }).__opened.push([url, target, features]);
      return null;
    }) as typeof window.open;
  });
  await page.goto(workspace);
  await page.getByRole("button", { name: "Lab 열기(별도 origin)" }).click();
  await expect.poll(() => page.evaluate(() => (window as unknown as { __opened: unknown[] }).__opened.length)).toBe(1);
  const [url, target, features] = (await page.evaluate(() => (window as unknown as { __opened: string[][] }).__opened[0])) as string[];
  expect(new URL(url).origin).toBe(GATEWAY);
  expect(new URL(url).origin).not.toBe(new URL(page.url()).origin);
  expect(target).toBe("_blank");
  expect(features).toContain("noopener");
  await expect(page.locator("iframe")).toHaveCount(0);
  await expect(page.getByText("격리가 검증되지 않은 개발 환경")).toBeVisible();
});

test("after a reconnect or reload the stream resumes after the last seen event", async ({ page }) => {
  const api = new FakeApi();
  api.evidence = [1, 2, 3, 4].map((seq) => api.evidenceItem(seq, `Event${seq}`, { seq }));
  api.streamBatches = [[1, 2], [3]];
  await api.install(page);
  await page.goto(workspace);
  await page.getByRole("tab", { name: "로그" }).click();
  await expect(page.locator(".timeline li")).toHaveCount(3, { timeout: 10_000 });
  expect(api.streamRequests.slice(0, 2)).toEqual([null, "2"]);

  api.streamBatches = [[4]];
  await page.reload();
  await page.getByRole("tab", { name: "로그" }).click();
  await expect.poll(() => api.streamRequests.at(-1), { timeout: 10_000 }).toBe("3");
  await expect(page.locator(".timeline li")).toHaveCount(1);
  await expect(page.locator(".timeline li").first()).toHaveAttribute("data-seq", "4");
});

test("a learner failure and a platform failure read differently", async ({ page }) => {
  const api = new FakeApi();
  await api.install(page);
  await page.goto(workspace);
  const submit = async () => {
    await page.getByRole("textbox", { name: "플래그", exact: true }).fill("SD{synthetic-guess}");
    await page.getByRole("button", { name: "제출", exact: true }).click();
  };

  api.submitStatus = 503;
  await submit();
  await expect(page.locator('[data-kind="platform"]')).toContainText("학습자의 실패가 아니며");
  await expect(page.getByRole("button", { name: "다시 시도" })).toHaveCount(0);

  api.submitStatus = 422;
  await submit();
  await expect(page.locator('[data-kind="request"]')).toContainText("content.flag");

  api.submitStatus = 202;
  api.evaluation = { id: ids.submission, revision: 1, policyVersion: "ctf-objective/1", verdict: "FAIL", dimensions: [], gates: [{ key: "flag", result: "FAIL" }], demo: true };
  await submit();
  await expect(page.locator('[data-verdict="FAIL"]')).toContainText("아직 기준을 넘지 못했습니다: 플래그");
  await expect(page.getByTestId("announcer")).toContainText("채점 결과: FAIL");
});

test("a SYSTEM_ERROR result is explained as the platform's problem, with the demo label kept", async ({ page }) => {
  const api = new FakeApi();
  api.evaluation = { id: ids.submission, revision: 1, policyVersion: "ctf-objective/1", verdict: "SYSTEM_ERROR", dimensions: [], gates: [{ key: "flag", result: "PASS" }, { key: "objective", result: "INCONCLUSIVE" }], demo: true };
  await api.install(page);
  await page.goto(workspace);
  await page.getByRole("textbox", { name: "플래그", exact: true }).fill("SD{synthetic}");
  await page.getByRole("button", { name: "제출", exact: true }).click();
  const result = page.locator('[data-verdict="SYSTEM_ERROR"]');
  await expect(result).toContainText("학습자 실패가 아님");
  await expect(result).toContainText("데모 결과");
  await expect(result.locator('[data-gate="objective"]')).toHaveAttribute("data-result", "INCONCLUSIVE");
});

test("completion comes from the server, and Purple starts as a new linked Session", async ({ page }) => {
  const api = new FakeApi();
  await api.install(page);
  await page.goto(workspace);
  await page.getByRole("button", { name: "완료하고 Lab 종료" }).click();
  await expect(page.getByRole("list", { name: "아직 충족되지 않은 완료 조건" })).toContainText("목표 확인");
  await expect(page.getByText("SUBMITTED")).toHaveCount(0);

  await page.getByRole("link", { name: "Purple로 이어가기(새 연결 Session)" }).click();
  await expect(page.getByText("이전 Session과 연결된 새 Session으로 시작합니다")).toBeVisible();
  await page.getByRole("button", { name: "PURPLE로 시작" }).click();
  await expect(page.getByTestId("workspace")).toBeVisible();
  expect(api.createdSessions.at(-1)).toEqual({ scenarioVersionId: ids.version, mode: "PURPLE", parentSessionId: ids.session });
  await expect(page.getByRole("heading", { name: "대응 액션" })).toBeVisible();
});

test("Lab state changes are announced once, not on every poll", async ({ page }) => {
  const api = new FakeApi();
  api.labState = "PROVISIONING";
  await api.install(page);
  await page.goto(workspace);
  await expect(page.getByTestId("lab-state")).toContainText("PROVISIONING");
  api.labState = "READY";
  await expect(page.getByTestId("announcer")).toHaveText("Lab 상태: READY", { timeout: 10_000 });
  await expect(page.getByTestId("lab-state")).toContainText("READY");
});
