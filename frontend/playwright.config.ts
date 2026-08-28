import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against the assembled stack, not a mocked backend.
 *
 * The point of these tests is to catch the things unit tests cannot: that the frontend, the API,
 * the engine and Postgres agree with each other. Pointing them at fixtures would remove exactly
 * the risk they exist to cover.
 *
 * Start the stack first:  docker compose up --build -d
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env["CI"]),
  retries: process.env["CI"] ? 1 : 0,
  workers: 1,
  reporter: process.env["CI"] ? "github" : "list",
  timeout: 90_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL: process.env["FSP_WEB_URL"] ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
