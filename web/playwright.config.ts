import { defineConfig, devices } from "@playwright/test";

// Browsers live in web/.playwright (repo-local, git-ignored). Run: PLAYWRIGHT_BROWSERS_PATH=.playwright npm run e2e
process.env.PLAYWRIGHT_BROWSERS_PATH ??= ".playwright";

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:4173", trace: "off" },
  webServer: { command: "npm run build && npm run preview", url: "http://127.0.0.1:4173/app/", reuseExistingServer: false, timeout: 120_000 },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 900 } } },
    { name: "phone", use: { ...devices["Pixel 7"] } },
  ],
});
