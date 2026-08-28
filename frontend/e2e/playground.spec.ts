import { expect, test, type Page } from "@playwright/test";

/**
 * The path a user actually takes: start from a preset, look at the model, run it, watch the
 * numbers move, save a checkpoint, and branch from it.
 *
 * One long test rather than several short ones. Each step depends on the last, and splitting them
 * would mean either re-doing the setup four times or sharing state between tests, both of which
 * make failures harder to read than the sequence they describe.
 */
test("build a system from a preset, run it, and branch from a checkpoint", async ({ page }) => {
  await page.goto("/systems");

  await test.step("start from the office team preset", async () => {
    // Scoped to the presets list: a system created from this preset carries the same name, so an
    // unscoped match would be ambiguous the second time this test is ever run.
    const preset = page
      .getByTestId("preset-list")
      .locator("li")
      .filter({ hasText: "Office team of eight" });
    await expect(preset).toBeVisible();
    await preset.getByRole("button", { name: "Use this" }).click();

    await expect(page).toHaveURL(/\/systems\/[0-9a-f-]{36}/);
  });

  await test.step("the editor shows the model and reports it as valid", async () => {
    // Objects are drawn as nodes with their variables on them.
    await expect(page.locator(".react-flow__node").first()).toBeVisible();
    await expect(page.getByText("ana", { exact: false }).first()).toBeVisible();

    await expect(page.getByText("valid")).toBeVisible();
    await expect(page.getByText(/reinforcing|balancing|No feedback loops/)).toBeVisible();
  });

  await test.step("publish it and start a run", async () => {
    await expect(page.getByText("saved")).toBeVisible();
    await page.getByRole("button", { name: "Publish", exact: true }).click();
    await expect(page.getByText(/v\d+ published/)).toBeVisible();

    await page.getByRole("button", { name: /Publish & run/ }).click();
    await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}/);
  });

  const runUrl = page.url();

  await test.step("the run advances and charts what it did", async () => {
    await expect(page.getByRole("img", { name: "Time series chart" })).toBeVisible();
    await expect(page.getByText("live")).toBeVisible();
    await expect.poll(() => currentTick(page), { timeout: 30_000 }).toBeGreaterThan(5);
  });

  await test.step("pause it, and it stays paused", async () => {
    await page.getByRole("button", { name: /Pause/ }).click();
    await expect(page.getByText("paused")).toBeVisible();

    const settled = await currentTick(page);
    await page.waitForTimeout(1200);
    expect(await currentTick(page)).toBe(settled);
  });

  await test.step("stepping advances by exactly the amount asked for", async () => {
    const before = await currentTick(page);
    await page.getByLabel("Ticks per step").fill("7");
    await page.getByRole("button", { name: /Step/ }).click();

    await expect.poll(() => currentTick(page)).toBe(before + 7);
  });

  await test.step("the log explains what happened", async () => {
    await expect(page.getByRole("heading", { name: "What happened" })).toBeVisible();
    await expect(page.locator("li", { hasText: /event|met|clashed|recalled/ }).first()).toBeVisible();
  });

  await test.step("take a checkpoint and branch from it", async () => {
    await page.getByRole("button", { name: /Checkpoint/ }).click();

    const checkpointRow = page.locator("li", { hasText: /^tick/ }).first();
    await expect(checkpointRow).toBeVisible();

    await checkpointRow.getByRole("button", { name: "Fork" }).click();
    await expect(page.getByText("Branches")).toBeVisible();

    // The original is untouched; the branch is a separate run at the same tick.
    await expect(page).toHaveURL(runUrl);
  });

  await test.step("the relationship matrix shows who thinks what of whom", async () => {
    await page.getByRole("button", { name: "relationships" }).click();
    await expect(page.getByRole("img", { name: "Relationship matrix" })).toBeVisible();
  });

  await test.step("memories are inspectable", async () => {
    await page.getByRole("button", { name: "memories" }).click();
    await expect(page.getByRole("heading", { name: "Memories" })).toBeVisible();
  });
});

test("a run survives a reload and keeps its identity", async ({ page }) => {
  await page.goto("/runs");

  const firstRun = page.locator("li").filter({ hasText: /seed/ }).first();
  await expect(firstRun).toBeVisible();
  await firstRun.getByRole("link").first().click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}/);
  // Seeds are signed 64-bit values, so roughly half of the random ones start with a minus.
  const seedText = /seed -?\d/;
  const seedBefore = await page.getByText(seedText).textContent();

  await page.reload();

  await expect(page.getByText(seedText)).toHaveText(seedBefore ?? "");
});

/** Reads the tick counter from the control bar. */
async function currentTick(page: Page): Promise<number> {
  const text = await page.getByTestId("run-tick").textContent();
  return Number((text ?? "0").replace(/[^\d]/g, ""));
}
