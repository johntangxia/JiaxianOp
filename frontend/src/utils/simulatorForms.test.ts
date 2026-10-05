import { describe, expect, it } from "vitest";
import {
  buildBackfillBody,
  buildRealtimeBody,
  defaultBackfillForm,
  defaultRealtimeForm,
  validateBackfillForm,
} from "./simulatorForms";

describe("defaultBackfillForm", () => {
  it("ends today (exclusive) and starts on the first day of the month 18 months earlier", () => {
    const form = defaultBackfillForm(new Date(2026, 9, 5));

    expect(form.historyEnd).toBe("2026-10-05");
    expect(form.historyStart).toBe("2025-04-01");
    expect(form).toMatchObject({
      batchId: "demo", employeeCount: 1000, accountCount: 100, teamAccountRatio: 0.25,
      contentCount: 1500, randomSeed: 7, personas: true,
    });
  });
});

describe("validateBackfillForm", () => {
  const valid = defaultBackfillForm(new Date(2026, 9, 5));

  it("accepts the defaults", () => {
    expect(validateBackfillForm(valid)).toBeNull();
  });

  it("rejects bad values with a Chinese message", () => {
    expect(validateBackfillForm({ ...valid, batchId: "  " })).toBe("批次名不能为空");
    expect(validateBackfillForm({ ...valid, employeeCount: 0 })).toBe("员工数必须是正整数");
    expect(validateBackfillForm({ ...valid, accountCount: 2.5 })).toBe("账号数必须是正整数");
    expect(validateBackfillForm({ ...valid, contentCount: -1 })).toBe("内容数必须是正整数");
    expect(validateBackfillForm({ ...valid, teamAccountRatio: 1.5 })).toBe("团队账号比例必须在 0 到 1 之间");
    expect(validateBackfillForm({ ...valid, randomSeed: 1.5 })).toBe("随机种子必须是整数");
    expect(validateBackfillForm({ ...valid, historyStart: "2026-09-06", historyEnd: "2026-09-05" }))
      .toBe("开始日期不能晚于结束日期");
  });
});

describe("request bodies", () => {
  it("builds the backfill body with a trimmed batch id", () => {
    const body = buildBackfillBody({ ...defaultBackfillForm(new Date(2026, 9, 5)), batchId: " demo " });

    expect(body).toEqual({
      batchId: "demo", employeeCount: 1000, accountCount: 100, teamAccountRatio: 0.25, contentCount: 1500,
      historyStart: "2025-04-01", historyEnd: "2026-10-05", randomSeed: 7, personas: true,
    });
  });

  it("omits a blank realtime batch id", () => {
    expect(defaultRealtimeForm()).toEqual({ batchId: "", intervalMillis: 2000, eventsPerBatch: 10 });
    expect(buildRealtimeBody({ batchId: "  ", intervalMillis: 500, eventsPerBatch: 3 }))
      .toEqual({ intervalMillis: 500, eventsPerBatch: 3 });
    expect(buildRealtimeBody({ batchId: " live ", intervalMillis: 500, eventsPerBatch: 3 }))
      .toEqual({ batchId: "live", intervalMillis: 500, eventsPerBatch: 3 });
  });
});
