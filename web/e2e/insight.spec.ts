import { expect, test } from "@playwright/test";
import { FakeApi, ids } from "./fakeApi";

const report = `/app/sessions/${ids.purple}/report`;

test("a learner follows a score to its evidence and sees why the next scenario is recommended", async ({ page }) => {
  const api = new FakeApi();
  await api.install(page);
  await page.goto(report);
  const detection = page.locator('[data-dimension="detection"]');
  await expect(detection).toContainText("✔ 통과");
  await expect(detection).toContainText("모델 재계산(SIMULATED)");
  await expect(page.locator('[data-dimension="attack"]')).toContainText("시도하지 않음");
  await expect(page.getByTestId("exposure")).toHaveText("힌트나 이전 Session의 도움을 받음");
  await expect(page.getByRole("list", { name: "판정 범위와 제한" })).toContainText("실제 Lab을 조치한 결과가 아닙니다");
  const recommendation = page.locator(`[data-recommendation="${ids.version}"]`);
  await expect(recommendation.getByRole("list", { name: "추천 이유" })).toContainText("이 역량의 근거가 아직 부족합니다");
  await expect(recommendation).toContainText("새 계열 100%");

  await detection.getByRole("link", { name: "근거 1" }).click();
  await expect(page).toHaveURL(new RegExp(`/replay\\?evidence=${api.evidenceIds[2]}`));
  await expect(page.getByTestId("current-item")).toContainText("#3 TEST_RESULT [모델 재계산(SIMULATED)]");
  await expect(page.locator('[data-seq="3"]')).toHaveAttribute("aria-current", "true");
});

test("replay keeps recorded facts and model recomputation apart and marks expired artifacts", async ({ page }) => {
  await new FakeApi().install(page);
  await page.goto(`/app/sessions/${ids.purple}/replay`);
  await expect(page.locator('[data-seq="2"]')).toContainText("[학습자 보고]");
  await expect(page.locator('[data-seq="2"]')).toContainText("원본 만료");
  await expect(page.getByRole("note")).toContainText("ARTIFACT_EXPIRED");
  const state = page.getByTestId("model-state");
  await expect(state).toHaveAttribute("data-representation", "SIMULATED");
  await expect(state).toContainText("tick 3에서 재계산");
  await expect(page.getByTestId("digest-match")).toHaveText("기록된 상태와 일치");

  // Keyboard: the slider and the previous/next buttons both move through ticks.
  const slider = page.getByRole("slider");
  await slider.focus();
  await page.keyboard.press("Home");
  await expect(page.getByTestId("digest-match")).toHaveText("기록 전(초기 상태)");
  await page.getByRole("button", { name: "다음 tick" }).click();
  await expect(state).toContainText("tick 0에서 재계산");
  await page.getByRole("button", { name: "다음 항목" }).click();
  await expect(page.getByTestId("current-item")).toContainText("#2");
});

test("UNKNOWN is shown as missing evidence, separately from confidence", async ({ page }) => {
  await new FakeApi().install(page);
  await page.goto("/app/skills");
  const detection = page.locator('[data-skill="DETECTION"]');
  await expect(detection.locator('[data-cell="level"]')).toHaveText("미측정(근거 부족)");
  await expect(detection.locator('[data-cell="confidence"]')).toHaveText("낮음");
  await expect(detection).toContainText("100%");
  await expect(page.locator('[data-skill="SECURE_PATCHING"] [data-cell="level"]')).toHaveText("연습 중");
  await expect(page.getByText("성공률은 숙련 확률이 아닙니다")).toBeVisible();
});

test("a re-graded report shows why it changed and keeps the earlier revision readable", async ({ page }) => {
  const api = new FakeApi();
  api.reports = [api.reportRevision(1, "50000000-0000-4000-8000-000000000001"), api.reportRevision(2, "50000000-0000-4000-8000-000000000002", "평가가 바뀌었습니다(재채점 또는 새 결과). 이전 revision 1은 그대로 남아 있습니다.")];
  await api.install(page);
  await page.goto(report);
  await expect(page.getByText("revision 2 ·")).toBeVisible();
  await expect(page.getByText("이전 revision 1은 그대로 남아 있습니다")).toBeVisible();
  await page.getByRole("link", { name: "revision 1 보기" }).click();
  await expect(page.getByText("revision 1 ·")).toBeVisible();
  await expect(page.getByText("이전 revision 1은 그대로")).toHaveCount(0);
});

test("on a phone the report fits the screen; wide tables scroll inside their own box", async ({ page }, info) => {
  test.skip(info.project.name !== "phone", "phone viewport only");
  await new FakeApi().install(page);
  await page.goto(report);
  await expect(page.locator('[data-dimension="detection"]')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  await expect(page.getByRole("region", { name: "평가 차원 표" }).or(page.getByLabel("평가 차원 표"))).toBeVisible();
});
