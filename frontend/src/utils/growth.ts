import type {
  GrowthAlert,
  GrowthAlertStatus,
  GrowthCreateTaskBody,
  GrowthDepartmentRow,
  GrowthEntityType,
  GrowthHotRow,
  GrowthMetric,
  GrowthTask,
  GrowthTaskStatus,
  GrowthTaskType,
} from "../api/types";
import { formatPercent } from "./channelMetrics";
import { formatNumber } from "./format";

const KIND_LABELS: Record<string, string> = {
  CHANNEL_NO_OWN_ENTRY_TRAFFIC: "频道无自主流量",
  CHANNEL_LOW_OWN_ENTRY_SHARE: "频道自主分发偏低",
  CHANNEL_HIGH_COMPOSITE_DEPENDENCY: "频道依赖综合频道",
  CHANNEL_LOW_OWN_CTR: "频道自主点击率低",
  CHANNEL_LOW_INTERACTION: "频道互动偏低",
  CHANNEL_LOW_SUPPLY: "频道供给不足",
  CHANNEL_PV_DROP: "频道 PV 下滑",
  CHANNEL_LOW_CTR: "综合频道点击率低",
  CHANNEL_CLICK_DROP: "综合频道点击下滑",
  OVERALL_DAU_DROP: "全社区 DAU 下滑",
  OVERALL_LOW_ACTIVATION: "新成员激活偏低",
  DEPARTMENT_LOW_PENETRATION: "部门渗透率偏低",
  BLOGGER_CHURN_RISK: "博主流失预警",
};

export function alertKindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? kind;
}

export const ALERT_STATUS_LABELS: Record<GrowthAlertStatus, string> = { OPEN: "待处理", ACKED: "已确认", CLOSED: "已关闭" };
export const TASK_STATUS_LABELS: Record<GrowthTaskStatus, string> = { TODO: "待办", DOING: "进行中", DONE: "完成", CANCELLED: "取消" };
export const TASK_TYPE_LABELS: Record<GrowthTaskType, string> = {
  PUSH_HOT_CONTENT: "推热点",
  IMPROVE_OWN_ENTRY: "提升自主分发",
  RECALL_LURKERS: "召回沉默读者",
  ONBOARD_NEWCOMERS: "带新人",
  ENCOURAGE_POSTING: "鼓励发帖",
  OTHER: "其他",
};
export const ENTITY_TYPE_LABELS: Record<GrowthEntityType, string> = {
  CHANNEL: "频道",
  ACCOUNT: "账号",
  BLOGGER: "博主",
  DEPARTMENT: "部门",
  OVERALL: "全社区",
};
export const METRIC_LABELS: Record<GrowthMetric, string> = {
  PV: "PV",
  OWN_ENTRY_SHARE: "自主分发占比",
  OWN_CTR: "自主入口点击率",
  INTERACTION_RATE: "互动率",
  POSTS: "发帖数",
  PENETRATION: "渗透率",
  ACTIVE_USERS: "活跃人数",
  ACTIVE_RATE: "活跃率",
};

/** 每类对象可以跟踪的指标，与后端 TaskMetric 的适用范围一致。 */
export const METRICS_BY_ENTITY: Record<GrowthEntityType, GrowthMetric[]> = {
  CHANNEL: ["PV", "OWN_ENTRY_SHARE", "OWN_CTR", "INTERACTION_RATE", "POSTS"],
  ACCOUNT: ["PV", "INTERACTION_RATE", "POSTS"],
  BLOGGER: ["PV", "INTERACTION_RATE", "POSTS"],
  DEPARTMENT: ["PENETRATION", "ACTIVE_USERS"],
  OVERALL: ["ACTIVE_USERS", "ACTIVE_RATE"],
};

const PERCENT_METRICS = new Set<GrowthMetric>(["OWN_ENTRY_SHARE", "OWN_CTR", "INTERACTION_RATE", "PENETRATION", "ACTIVE_RATE"]);

/** 比例类指标显示成百分比，计数类显示成带千分位的整数；缺失显示"—"。 */
export function formatMetricValue(metric: GrowthMetric, value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return PERCENT_METRICS.has(metric) ? formatPercent(value) : formatNumber(value);
}

/** 表单里目标值的输入单位：比例类指标让用户输入百分数（5 表示 5%），提交时除以 100。 */
export function isPercentMetric(metric: GrowthMetric): boolean {
  return PERCENT_METRICS.has(metric);
}

export function targetToInput(metric: GrowthMetric, value: number): number {
  return isPercentMetric(metric) ? Math.round(value * 10000) / 100 : value;
}

export function targetFromInput(metric: GrowthMetric, input: number): number {
  return isPercentMetric(metric) ? input / 100 : input;
}

/** 进度显示成百分数，缺失显示"—"。 */
export function progressText(progress: number | null | undefined): string {
  return progress === null || progress === undefined ? "—" : `${Math.round(progress * 100)}%`;
}

/** 进度条的百分比，限制在 0–100。 */
export function progressBar(progress: number | null | undefined): number {
  return progress === null || progress === undefined ? 0 : Math.min(100, Math.max(0, Math.round(progress * 100)));
}

/** 未完成（待办、进行中）且到期日早于统计日。 */
export function isOverdue(task: Pick<GrowthTask, "dueDate" | "status">, asOf: string): boolean {
  return task.dueDate !== null && task.dueDate.slice(0, 10) < asOf && (task.status === "TODO" || task.status === "DOING");
}

/** 创建任务对话框的预填内容；目标值由人来填。 */
export type TaskDraft = Pick<GrowthCreateTaskBody, "title" | "taskType" | "entityType" | "metric"> &
  Partial<Pick<GrowthCreateTaskBody, "entityId" | "entityName" | "alertId" | "note">>;

/** 从告警预填任务：按告警类型给出任务类型和要跟踪的指标；综合频道没有内容，退回到全社区活跃率。 */
export function draftFromAlert(alert: GrowthAlert): TaskDraft {
  const title = `处理告警：${alert.entityName} ${alertKindLabel(alert.kind)}`;
  const base = { title, alertId: alert.id, note: alert.message.slice(0, 480) };
  switch (alert.kind) {
    case "CHANNEL_NO_OWN_ENTRY_TRAFFIC":
    case "CHANNEL_LOW_OWN_ENTRY_SHARE":
    case "CHANNEL_HIGH_COMPOSITE_DEPENDENCY":
      return { ...base, taskType: "IMPROVE_OWN_ENTRY", entityType: "CHANNEL", entityId: alert.entityId ?? undefined, metric: "OWN_ENTRY_SHARE" };
    case "CHANNEL_LOW_OWN_CTR":
      return { ...base, taskType: "IMPROVE_OWN_ENTRY", entityType: "CHANNEL", entityId: alert.entityId ?? undefined, metric: "OWN_CTR" };
    case "CHANNEL_LOW_INTERACTION":
      return { ...base, taskType: "OTHER", entityType: "CHANNEL", entityId: alert.entityId ?? undefined, metric: "INTERACTION_RATE" };
    case "CHANNEL_LOW_SUPPLY":
      return { ...base, taskType: "ENCOURAGE_POSTING", entityType: "CHANNEL", entityId: alert.entityId ?? undefined, metric: "POSTS" };
    case "CHANNEL_PV_DROP":
      return { ...base, taskType: "PUSH_HOT_CONTENT", entityType: "CHANNEL", entityId: alert.entityId ?? undefined, metric: "PV" };
    case "OVERALL_DAU_DROP":
      return { ...base, taskType: "OTHER", entityType: "OVERALL", metric: "ACTIVE_USERS" };
    case "OVERALL_LOW_ACTIVATION":
      return { ...base, taskType: "ONBOARD_NEWCOMERS", entityType: "OVERALL", metric: "ACTIVE_RATE" };
    case "DEPARTMENT_LOW_PENETRATION":
      return { ...base, taskType: "RECALL_LURKERS", entityType: "DEPARTMENT", entityName: alert.entityName, metric: "PENETRATION" };
    case "BLOGGER_CHURN_RISK":
      return { ...base, taskType: "ENCOURAGE_POSTING", entityType: "BLOGGER", entityId: alert.entityId ?? undefined, metric: "PV" };
    default:
      return { ...base, taskType: "OTHER", entityType: "OVERALL", metric: "ACTIVE_RATE" };
  }
}

/** 从热点/潜力内容预填：给内容所在频道定一个 PV 目标。 */
export function draftFromContent(row: GrowthHotRow, potential: boolean): TaskDraft {
  return {
    title: `${potential ? "再推一把" : "推热点"}：内容 #${row.contentId}（${row.channelName}）`,
    taskType: "PUSH_HOT_CONTENT",
    entityType: "CHANNEL",
    entityId: row.channelId,
    metric: "PV",
  };
}

/** 从部门渗透率预填：召回这个部门的沉默读者。 */
export function draftFromDepartment(row: GrowthDepartmentRow): TaskDraft {
  return {
    title: `提升 ${row.dept} 渗透率`,
    taskType: "RECALL_LURKERS",
    entityType: "DEPARTMENT",
    entityName: row.dept,
    metric: "PENETRATION",
  };
}
