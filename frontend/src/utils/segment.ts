import type { Segment } from "../api/types";

export type TagType = "success" | "primary" | "warning" | "danger" | "info";

export interface SegmentInfo {
  value: Segment;
  name: string;
  tagType: TagType;
}

/** 分层标签的展示顺序、中文名和颜色（和后端 Segment 枚举一致）。 */
export const SEGMENTS: SegmentInfo[] = [
  { value: "STAR_KOL", name: "明星KOL", tagType: "warning" },
  { value: "STEADY", name: "稳定贡献者", tagType: "success" },
  { value: "RISING", name: "成长潜力新人", tagType: "primary" },
  { value: "CHURN_RISK", name: "流失预警", tagType: "danger" },
  { value: "DORMANT", name: "沉寂用户", tagType: "info" },
  { value: "OBSERVE", name: "观察中", tagType: "info" },
];

export function segmentName(segment: Segment): string {
  return SEGMENTS.find((s) => s.value === segment)?.name ?? segment;
}

export function segmentTagType(segment: Segment): TagType {
  return SEGMENTS.find((s) => s.value === segment)?.tagType ?? "info";
}
