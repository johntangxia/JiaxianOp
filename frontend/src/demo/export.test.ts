import { expect, it } from "vitest";
import { demoExportCsv } from "./export";

it("exports only anonymous rows from the published sample", async () => {
  const csv = await demoExportCsv("/api/users/export?sort=VIEWS&stage=ACTIVE");
  expect(csv).toContain("演示用户");
  expect(csv).not.toContain("员工-");
  expect(csv.startsWith("\ufeff")).toBe(true);
});
