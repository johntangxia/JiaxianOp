import { getJson, sendJson } from "./http";
import type { PointsConfigItem } from "./types";

export function fetchPointsConfig(): Promise<PointsConfigItem[]> {
  return getJson<PointsConfigItem[]>("/api/admin/points-config");
}

export function updateWeight(actionType: string, weight: number): Promise<PointsConfigItem> {
  return sendJson<PointsConfigItem>("PUT", `/api/admin/points-config/${encodeURIComponent(actionType)}`, {
    body: { weight },
  });
}

export function deleteWeightVersion(id: number): Promise<void> {
  return sendJson<void>("DELETE", `/api/admin/points-config/versions/${id}`);
}
