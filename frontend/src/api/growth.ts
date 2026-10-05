import { getJson, sendJson } from "./http";
import type {
  GrowthAlert,
  GrowthAlertList,
  GrowthAlertRebuild,
  GrowthAlertStatus,
  GrowthCreateTaskBody,
  GrowthDepartmentReport,
  GrowthEntityType,
  GrowthHotReport,
  GrowthTaskDetail,
  GrowthTaskList,
  GrowthTaskStatus,
  GrowthTaskView,
  GrowthUpdateTaskBody,
} from "./types";

export function fetchDepartments(days: number, asOf?: string): Promise<GrowthDepartmentReport> {
  return getJson<GrowthDepartmentReport>("/api/growth/departments", { days, asOf });
}

export function fetchHotContents(limit: number, asOf?: string): Promise<GrowthHotReport> {
  return getJson<GrowthHotReport>("/api/growth/hot-contents", { limit, asOf });
}

export interface AlertQuery {
  status?: GrowthAlertStatus;
  entityType?: GrowthEntityType;
  limit: number;
  offset: number;
}

export function fetchAlerts(query: AlertQuery): Promise<GrowthAlertList> {
  return getJson<GrowthAlertList>("/api/growth/alerts", { ...query });
}

export function rebuildAlerts(asOf?: string): Promise<GrowthAlertRebuild> {
  return sendJson<GrowthAlertRebuild>("POST", "/api/admin/growth/alerts/rebuild", { params: { asOf } });
}

export function updateAlertStatus(id: number, status: GrowthAlertStatus): Promise<GrowthAlert> {
  return sendJson<GrowthAlert>("PUT", `/api/admin/growth/alerts/${id}/status`, { body: { status } });
}

export function fetchTasks(status: GrowthTaskStatus | undefined, limit: number, offset: number): Promise<GrowthTaskList> {
  return getJson<GrowthTaskList>("/api/growth/tasks", { status, limit, offset });
}

export function fetchTask(id: number): Promise<GrowthTaskDetail> {
  return getJson<GrowthTaskDetail>(`/api/growth/tasks/${id}`);
}

export function createTask(body: GrowthCreateTaskBody): Promise<GrowthTaskView> {
  return sendJson<GrowthTaskView>("POST", "/api/admin/growth/tasks", { body });
}

export function updateTask(id: number, body: GrowthUpdateTaskBody): Promise<GrowthTaskView> {
  return sendJson<GrowthTaskView>("PUT", `/api/admin/growth/tasks/${id}`, { body });
}

export function deleteTask(id: number): Promise<void> {
  return sendJson<void>("DELETE", `/api/admin/growth/tasks/${id}`);
}
