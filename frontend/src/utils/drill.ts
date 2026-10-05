import type { RouteLocationRaw } from "vue-router";
import type { CompareType, Comparison, CompareSubject } from "../api/types";
import { formatPercent, formatSeconds } from "./channelMetrics";
import { formatNumber } from "./format";

export interface Range {
  from: string;
  to: string;
}

// ---- 路由跳转（范围通过 query 一路传递） ----

export function channelLink(id: number, range: Range): RouteLocationRaw {
  return { name: "channel-detail", params: { id }, query: { from: range.from, to: range.to } };
}

export function accountLink(id: number, range: Range): RouteLocationRaw {
  return { name: "account-detail", params: { id }, query: { from: range.from, to: range.to } };
}

export function bloggerLink(id: number, range: Range): RouteLocationRaw {
  return { name: "blogger-detail", params: { id }, query: { from: range.from, to: range.to } };
}

export function contentLink(id: number, range: Range): RouteLocationRaw {
  return { name: "content-detail", params: { id }, query: { from: range.from, to: range.to } };
}

export function compareLink(type: CompareType, ids: number[], range: Range): RouteLocationRaw {
  return { name: "compare", query: { type, ids: ids.join(","), from: range.from, to: range.to } };
}

/** 路由 query 里的日期范围；缺失或不是 yyyy-MM-dd 时返回 null。 */
export function rangeFromQuery(query: Record<string, unknown>): Range | null {
  const iso = /^\d{4}-\d{2}-\d{2}$/;
  const from = String(query.from ?? "");
  const to = String(query.to ?? "");
  return iso.test(from) && iso.test(to) ? { from, to } : null;
}

/** 区间超过 45 天默认按周看，按天太碎。 */
export function defaultGranularity(range: Range): "DAY" | "WEEK" {
  const days = (new Date(range.to).getTime() - new Date(range.from).getTime()) / 86_400_000 + 1;
  return days > 45 ? "WEEK" : "DAY";
}

// ---- 对比 ----

/**
 * 一行指标里最优项的下标（并列都算）；缺失值不参与；全部缺失返回空数组。
 * higherIsBetter 为 false 时数值越小越好（如头部集中度、取关数）。
 */
export function bestIndexes(values: Array<number | null | undefined>, higherIsBetter: boolean): number[] {
  const present = values.map((v, i) => ({ v, i })).filter((x): x is { v: number; i: number } => typeof x.v === "number");
  if (present.length === 0) {
    return [];
  }
  const best = higherIsBetter ? Math.max(...present.map((x) => x.v)) : Math.min(...present.map((x) => x.v));
  return present.filter((x) => x.v === best).map((x) => x.i);
}

interface MetricDef {
  key: string;
  label: string;
  higherIsBetter: boolean;
  /** 仅这些对象类型有这个指标；省略表示都有 */
  only?: CompareType;
  pick: (subject: CompareSubject) => number | null | undefined;
  format: (value: number | null | undefined) => string;
}

const num = (value: number | null | undefined): string => (value === null || value === undefined ? "—" : formatNumber(value));

export const COMPARE_METRICS: MetricDef[] = [
  { key: "pv", label: "PV", higherIsBetter: true, pick: (s) => s.current.content.pv, format: num },
  { key: "uv", label: "UV", higherIsBetter: true, pick: (s) => s.current.content.uv, format: num },
  { key: "interactionRate", label: "互动率", higherIsBetter: true, pick: (s) => s.current.content.interactionRate, format: (v) => formatPercent(v) },
  { key: "ownEntryShare", label: "自主分发占比", higherIsBetter: true, pick: (s) => s.current.content.ownEntryShare, format: (v) => formatPercent(v) },
  { key: "compositeDependency", label: "综合依赖度", higherIsBetter: false, pick: (s) => s.current.content.compositeDependency, format: (v) => formatPercent(v) },
  { key: "top1Share", label: "头部集中度", higherIsBetter: false, pick: (s) => s.current.content.top1Share, format: (v) => formatPercent(v) },
  { key: "contents", label: "发帖数", higherIsBetter: true, pick: (s) => s.current.supply.contents, format: num },
  { key: "impressions", label: "内容曝光", higherIsBetter: true, pick: (s) => s.current.exposure.impressions, format: num },
  { key: "ctr", label: "内容点击率", higherIsBetter: true, pick: (s) => s.current.exposure.ctr, format: (v) => formatPercent(v) },
  { key: "dwell", label: "平均停留", higherIsBetter: true, pick: (s) => s.current.exposure.avgDwellSeconds, format: formatSeconds },
  { key: "completion", label: "读完/播完率", higherIsBetter: true, pick: (s) => s.current.exposure.completionRate, format: (v) => formatPercent(v) },
  { key: "ownImpressions", label: "自主入口曝光", higherIsBetter: true, only: "CHANNEL", pick: (s) => s.ownEntry?.impressions, format: num },
  { key: "ownCtr", label: "自主入口点击率", higherIsBetter: true, only: "CHANNEL", pick: (s) => s.ownEntry?.ctr, format: (v) => formatPercent(v) },
  { key: "followers", label: "关注者", higherIsBetter: true, only: "ACCOUNT", pick: (s) => s.current.followers?.followers, format: num },
  { key: "newFollowers", label: "新增关注", higherIsBetter: true, only: "ACCOUNT", pick: (s) => s.current.followers?.newFollowers, format: num },
  { key: "lostFollowers", label: "取关", higherIsBetter: false, only: "ACCOUNT", pick: (s) => s.current.followers?.lostFollowers, format: num },
];

export interface CompareCell {
  text: string;
  best: boolean;
  /** 相比上期的变化率（PV 一类绝对量才有），其余为 null */
  previous: number | null | undefined;
  current: number | null | undefined;
}

export interface CompareRow {
  key: string;
  label: string;
  cells: CompareCell[];
}

/** 对比表：每个指标一行，每个对象一格，标出最优项。 */
export function compareRows(comparison: Comparison): CompareRow[] {
  return COMPARE_METRICS.filter((m) => !m.only || m.only === comparison.type).map((metric) => {
    const values = comparison.subjects.map((s) => metric.pick(s));
    const best = new Set(bestIndexes(values, metric.higherIsBetter));
    // 全部对象的值都相同时没有"最优"可言
    const allSame = values.every((v) => v === values[0]);
    return {
      key: metric.key,
      label: metric.label,
      cells: comparison.subjects.map((subject, i) => ({
        text: metric.format(values[i]),
        best: best.has(i) && !allSame,
        current: values[i],
        previous: metric.key === "pv" ? subject.previous.content.pv : metric.key === "uv" ? subject.previous.content.uv : null,
      })),
    };
  });
}
