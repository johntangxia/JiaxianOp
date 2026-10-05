import { describe, expect, it } from "vitest";
import type { GrowthAlert, GrowthDepartmentRow, GrowthHotRow } from "../api/types";
import {
  alertKindLabel,
  draftFromAlert,
  draftFromContent,
  draftFromDepartment,
  formatMetricValue,
  isOverdue,
  METRICS_BY_ENTITY,
  progressBar,
  progressText,
  targetFromInput,
  targetToInput,
} from "./growth";

function alert(kind: string, entityType: GrowthAlert["entityType"], entityId: number | null, entityName: string): GrowthAlert {
  return { id: 5, kind, severity: "WARN", entityType, entityId, entityName, periodEnd: "2025-09-04", message: "说明文字", status: "OPEN", createdAt: "", updatedAt: "" };
}

describe("labels", () => {
  it("translates known alert kinds and falls back to the raw kind", () => {
    expect(alertKindLabel("OVERALL_DAU_DROP")).toBe("全社区 DAU 下滑");
    expect(alertKindLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
  });
});

describe("formatMetricValue", () => {
  it("shows ratio metrics as percentages and counts with separators", () => {
    expect(formatMetricValue("OWN_ENTRY_SHARE", 0.0234)).toBe("2.3%");
    expect(formatMetricValue("PENETRATION", 0.5)).toBe("50.0%");
    expect(formatMetricValue("PV", 12345)).toBe("12,345");
    expect(formatMetricValue("POSTS", null)).toBe("—");
  });
});

describe("target input conversion", () => {
  it("lets users type percentages for ratio metrics", () => {
    expect(targetFromInput("OWN_ENTRY_SHARE", 5)).toBe(0.05);
    expect(targetToInput("OWN_ENTRY_SHARE", 0.05)).toBe(5);
    expect(targetToInput("PENETRATION", 0.1234)).toBe(12.34);
  });

  it("leaves counts untouched", () => {
    expect(targetFromInput("PV", 500)).toBe(500);
    expect(targetToInput("ACTIVE_USERS", 80)).toBe(80);
  });
});

describe("progress helpers", () => {
  it("shows progress as a percentage and clamps the bar", () => {
    expect(progressText(0.5)).toBe("50%");
    expect(progressText(1.5)).toBe("150%");
    expect(progressText(-0.2)).toBe("-20%");
    expect(progressText(null)).toBe("—");
    expect(progressBar(1.5)).toBe(100);
    expect(progressBar(-0.2)).toBe(0);
    expect(progressBar(0.37)).toBe(37);
    expect(progressBar(null)).toBe(0);
  });

  it("flags unfinished tasks whose due date has passed", () => {
    expect(isOverdue({ dueDate: "2025-08-30", status: "DOING" }, "2025-09-04")).toBe(true);
    expect(isOverdue({ dueDate: "2025-08-30T00:00:00", status: "TODO" }, "2025-09-04")).toBe(true);
    expect(isOverdue({ dueDate: "2025-08-30", status: "DONE" }, "2025-09-04")).toBe(false);
    expect(isOverdue({ dueDate: "2025-09-04", status: "TODO" }, "2025-09-04")).toBe(false);
    expect(isOverdue({ dueDate: null, status: "TODO" }, "2025-09-04")).toBe(false);
  });
});

describe("metric options per entity type", () => {
  it("matches what the backend accepts", () => {
    expect(METRICS_BY_ENTITY.CHANNEL).toContain("OWN_ENTRY_SHARE");
    expect(METRICS_BY_ENTITY.ACCOUNT).not.toContain("OWN_ENTRY_SHARE");
    expect(METRICS_BY_ENTITY.DEPARTMENT).toEqual(["PENETRATION", "ACTIVE_USERS"]);
    expect(METRICS_BY_ENTITY.OVERALL).toEqual(["ACTIVE_USERS", "ACTIVE_RATE"]);
  });
});

describe("task drafts", () => {
  it("maps channel diagnoses to a task type and metric and keeps the alert link", () => {
    const draft = draftFromAlert(alert("CHANNEL_LOW_OWN_ENTRY_SHARE", "CHANNEL", 12, "半导体"));

    expect(draft).toMatchObject({ taskType: "IMPROVE_OWN_ENTRY", entityType: "CHANNEL", entityId: 12, metric: "OWN_ENTRY_SHARE", alertId: 5 });
    expect(draft.title).toContain("半导体");
    expect(draft.note).toBe("说明文字");
    expect(draftFromAlert(alert("CHANNEL_LOW_OWN_CTR", "CHANNEL", 12, "半导体")).metric).toBe("OWN_CTR");
    expect(draftFromAlert(alert("CHANNEL_LOW_SUPPLY", "CHANNEL", 12, "半导体")).taskType).toBe("ENCOURAGE_POSTING");
    expect(draftFromAlert(alert("CHANNEL_PV_DROP", "CHANNEL", 12, "半导体")).metric).toBe("PV");
  });

  it("maps overall, department and blogger alerts", () => {
    expect(draftFromAlert(alert("OVERALL_DAU_DROP", "OVERALL", null, "全社区"))).toMatchObject({ entityType: "OVERALL", metric: "ACTIVE_USERS" });
    expect(draftFromAlert(alert("OVERALL_LOW_ACTIVATION", "OVERALL", null, "全社区"))).toMatchObject({ taskType: "ONBOARD_NEWCOMERS", metric: "ACTIVE_RATE" });
    expect(draftFromAlert(alert("DEPARTMENT_LOW_PENETRATION", "DEPARTMENT", null, "光"))).toMatchObject({ entityType: "DEPARTMENT", entityName: "光", metric: "PENETRATION" });
    expect(draftFromAlert(alert("BLOGGER_CHURN_RISK", "BLOGGER", 33, "博主三三"))).toMatchObject({ entityType: "BLOGGER", entityId: 33, metric: "PV" });
  });

  it("falls back to the overall active rate for composite-channel and unknown alerts", () => {
    expect(draftFromAlert(alert("CHANNEL_LOW_CTR", "CHANNEL", 1, "推荐"))).toMatchObject({ entityType: "OVERALL", metric: "ACTIVE_RATE" });
    expect(draftFromAlert(alert("WHATEVER", "OVERALL", null, "全社区"))).toMatchObject({ entityType: "OVERALL" });
  });

  it("builds drafts from hot content and departments", () => {
    const row = { contentId: 77, channelId: 9, channelName: "光" } as GrowthHotRow;
    expect(draftFromContent(row, false)).toMatchObject({ taskType: "PUSH_HOT_CONTENT", entityType: "CHANNEL", entityId: 9, metric: "PV" });
    expect(draftFromContent(row, false).title).toContain("推热点");
    expect(draftFromContent(row, true).title).toContain("再推一把");

    const dept = { dept: "半导体" } as GrowthDepartmentRow;
    expect(draftFromDepartment(dept)).toMatchObject({ taskType: "RECALL_LURKERS", entityType: "DEPARTMENT", entityName: "半导体", metric: "PENETRATION" });
  });
});
