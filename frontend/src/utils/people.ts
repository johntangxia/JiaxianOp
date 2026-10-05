import type { RouteLocationRaw } from "vue-router";
import type {
  CreatorSortKey,
  CreatorStageKey,
  UserLifecycleStageKey,
  UserListSort,
  UserTransitionRow,
  UserValueSegmentKey,
} from "../api/types";

/** 用户 360 页面；asOf 可选，不带就用后端默认统计日。 */
export function userLink(id: number, asOf?: string): RouteLocationRaw {
  return asOf ? { name: "user-detail", params: { id }, query: { asOf } } : { name: "user-detail", params: { id } };
}

const LIFECYCLE_COLORS: Record<UserLifecycleStageKey, string> = {
  NEW: "#14a38b",
  ACTIVE: "#2f6fed",
  RETURNING: "#8b5cf6",
  LAPSING: "#f08c2c",
  CHURNED: "#d9363e",
  DORMANT: "#9aa3b2",
};

export function lifecycleColor(stage: UserLifecycleStageKey): string {
  return LIFECYCLE_COLORS[stage];
}

/** el-tag 的类型：健康的阶段用成功/主色，需要关注的用警告/危险，其余用 info。 */
export function lifecycleTagType(stage: UserLifecycleStageKey | null): "success" | "primary" | "warning" | "danger" | "info" {
  switch (stage) {
    case "NEW":
      return "success";
    case "ACTIVE":
    case "RETURNING":
      return "primary";
    case "LAPSING":
      return "warning";
    case "CHURNED":
      return "danger";
    default:
      return "info";
  }
}

const VALUE_COLORS: Record<UserValueSegmentKey, string> = {
  CORE: "#8b5cf6",
  ACTIVE: "#2f6fed",
  TO_WAKE: "#f08c2c",
  LOW_ACTIVE: "#14a38b",
  SLEEPING: "#9aa3b2",
};

export function valueColor(segment: UserValueSegmentKey): string {
  return VALUE_COLORS[segment];
}

export function valueTagType(segment: UserValueSegmentKey | null): "success" | "primary" | "warning" | "danger" | "info" {
  switch (segment) {
    case "CORE":
      return "success";
    case "ACTIVE":
      return "primary";
    case "TO_WAKE":
      return "warning";
    default:
      return "info";
  }
}

const CREATOR_STAGE_COLORS: Record<CreatorStageKey, string> = {
  INACTIVE: "#d5d9e0",
  READER: "#9aa3b2",
  INTERACTOR: "#2f6fed",
  CREATOR: "#14a38b",
  ACTIVE_CREATOR: "#f08c2c",
  KOL: "#8b5cf6",
};

export function creatorStageColor(stage: CreatorStageKey): string {
  return CREATOR_STAGE_COLORS[stage];
}

const SOURCE_LABELS: Record<string, string> = { ENTRY: "频道入口", SEARCH: "搜索", SHARE: "分享", OTHER: "其他" };

/** 浏览来源的中文名；不认识的原样返回。 */
export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source] ?? source;
}

export const CREATOR_SORTS: Array<{ value: CreatorSortKey; label: string }> = [
  { value: "PV", label: "按 PV" },
  { value: "POSTS", label: "按发帖数" },
  { value: "INTERACTION_RATE", label: "按互动率" },
  { value: "COMPLETION", label: "按读完率" },
  { value: "NEW_FOLLOWERS", label: "按新增关注" },
];

export const USER_SORTS: Array<{ value: UserListSort; label: string }> = [
  { value: "LAST_ACTIVE", label: "最近活跃" },
  { value: "VIEWS", label: "浏览数" },
  { value: "INTERACTIONS", label: "互动数" },
  { value: "POSTS", label: "发帖数" },
  { value: "ACTIVE_DAYS", label: "活跃天数" },
];

export interface MatrixCell {
  text: string;
  background: string;
  color: string;
}

/** 流转矩阵格：人数加占该行的比例；0 显示"·"；占比越高蓝色越深，超过 60% 用白字。 */
export function matrixCell(count: number, rowTotal: number): MatrixCell {
  if (count === 0 || rowTotal === 0) {
    return { text: "·", background: "#f5f7fa", color: "#c0c6d0" };
  }
  const share = Math.min(1, count / rowTotal);
  const alpha = 0.08 + 0.82 * share;
  return {
    text: `${count}（${(share * 100).toFixed(0)}%）`,
    background: `rgba(47, 111, 237, ${alpha.toFixed(2)})`,
    color: share > 0.6 ? "#fff" : "#1f2937",
  };
}

/** 流转矩阵里"留在原阶段"的人数（对角线）；此前非成员行没有对角线。 */
export function stayed(row: UserTransitionRow): number | null {
  if (row.from === null) {
    return null;
  }
  return row.to.find((cell) => cell.stage === row.from)?.count ?? 0;
}

/** 天数：整数不带小数，半天保留一位；缺失 → "—"。 */
export function formatDays(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return Number.isInteger(value) ? `${value} 天` : `${value.toFixed(1)} 天`;
}

/** 基尼系数的文字解读：<0.3 较均匀，<0.5 中等，其余较集中。 */
export function giniLabel(gini: number | null | undefined): string {
  if (gini === null || gini === undefined) {
    return "—";
  }
  const text = gini.toFixed(2);
  if (gini < 0.3) {
    return `${text}（较均匀）`;
  }
  return gini < 0.5 ? `${text}（中等集中）` : `${text}（高度集中）`;
}

/** yyyy-MM-ddTHH:mm:ss → MM-dd HH:mm。 */
export function shortTime(value: string): string {
  return value.replace("T", " ").slice(5, 16);
}

/** 周起点 yyyy-MM-dd → MM-dd。 */
export function weekLabel(weekStart: string): string {
  return weekStart.slice(5);
}
