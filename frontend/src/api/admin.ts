import { getJson, sendJson } from "./http";
import type { BackfillRequest, PvRebuildResult, RealtimeRequest, RealtimeStatus, SnapshotSummary } from "./types";

/** 历史回填会清空所有模拟数据；成功时后端返回一句文字。 */
export function backfill(body: BackfillRequest): Promise<string> {
  return sendJson<string>("POST", "/api/admin/simulator/backfill", { body });
}

export function startRealtime(body: RealtimeRequest): Promise<RealtimeStatus> {
  return sendJson<RealtimeStatus>("POST", "/api/admin/simulator/realtime/start", { body });
}

/** 停止后返回最终状态；本来就没在运行时后端返回一句文字，调用方应在停止后重新取状态。 */
export function stopRealtime(): Promise<RealtimeStatus | string> {
  return sendJson<RealtimeStatus | string>("POST", "/api/admin/simulator/realtime/stop");
}

export function fetchRealtimeStatus(): Promise<RealtimeStatus> {
  return getJson<RealtimeStatus>("/api/admin/simulator/realtime/status");
}

export function rebuildPv(from?: string, to?: string): Promise<PvRebuildResult> {
  return sendJson<PvRebuildResult>("POST", "/api/admin/analytics/pv/rebuild", { params: { from, to } });
}

export function snapshotAllLeaderboards(): Promise<SnapshotSummary> {
  return sendJson<SnapshotSummary>("POST", "/api/admin/analytics/leaderboard/snapshot");
}
