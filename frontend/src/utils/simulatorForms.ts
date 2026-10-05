import type { BackfillRequest, RealtimeRequest } from "../api/types";
import { parseIsoDate, toIsoDate } from "./dateRange";

export type BackfillForm = BackfillRequest;

export interface RealtimeForm {
  batchId: string;
  intervalMillis: number;
  eventsPerBatch: number;
}

/**
 * 回填默认值：结束日期 = 今天往前 30 天（回填的浏览行为会延续到发布后 30 天，结束得太靠近今天会产生"未来"的数据），
 * 开始日期 = 结束日期往前 18 个月的 1 号。
 */
export function defaultBackfillForm(today: Date = new Date()): BackfillForm {
  const end = new Date(today);
  end.setDate(end.getDate() - 30);
  const start = new Date(end.getFullYear(), end.getMonth() - 18, 1);
  return {
    batchId: "demo",
    employeeCount: 50,
    accountCount: 20,
    teamAccountRatio: 0.25,
    contentCount: 300,
    historyStart: toIsoDate(start),
    historyEnd: toIsoDate(end),
    randomSeed: 7,
    personas: true,
  };
}

const isPositiveInteger = (n: number): boolean => Number.isInteger(n) && n > 0;

/** 返回第一条问题的中文说明，没有问题返回 null。 */
export function validateBackfillForm(form: BackfillForm): string | null {
  if (!form.batchId.trim()) {
    return "批次名不能为空";
  }
  if (!isPositiveInteger(form.employeeCount)) {
    return "员工数必须是正整数";
  }
  if (!isPositiveInteger(form.accountCount)) {
    return "账号数必须是正整数";
  }
  if (!isPositiveInteger(form.contentCount)) {
    return "内容数必须是正整数";
  }
  if (!(form.teamAccountRatio >= 0 && form.teamAccountRatio <= 1)) {
    return "团队账号比例必须在 0 到 1 之间";
  }
  if (!Number.isInteger(form.randomSeed)) {
    return "随机种子必须是整数";
  }
  if (form.historyStart > form.historyEnd) {
    return "开始日期不能晚于结束日期";
  }
  return null;
}

export function buildBackfillBody(form: BackfillForm): BackfillRequest {
  return { ...form, batchId: form.batchId.trim() };
}

export function defaultRealtimeForm(): RealtimeForm {
  return { batchId: "", intervalMillis: 2000, eventsPerBatch: 10 };
}

/** 批次名留空时不发送，由后端生成。 */
export function buildRealtimeBody(form: RealtimeForm): RealtimeRequest {
  const batchId = form.batchId.trim();
  return {
    ...(batchId ? { batchId } : {}),
    intervalMillis: form.intervalMillis,
    eventsPerBatch: form.eventsPerBatch,
  };
}
