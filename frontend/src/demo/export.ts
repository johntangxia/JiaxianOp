import { demoGetJson } from "./api";

function csvCell(value: unknown): string {
  const text = value === null || value === undefined ? "" : String(value);
  const safe = /^[=+\-@]/.test(text) ? `'${text}` : text;
  return `"${safe.replace(/"/g, '""')}"`;
}

function csv(headers: string[], rows: unknown[][]): string {
  return "\ufeff" + [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

export async function demoExportCsv(url: string): Promise<string> {
  const parsed = new URL(url, "https://static-demo.invalid");
  const params = Object.fromEntries(parsed.searchParams.entries());
  if (parsed.pathname === "/api/users/export") {
    const data = await demoGetJson<{ users: Array<Record<string, unknown>> }>("/api/users/list", { ...params, limit: 1000, offset: 0 });
    return csv(["演示编号", "姓名", "部门", "生命周期", "价值分层", "最近活跃", "浏览", "互动", "发帖"], data.users.map((row) => [row.employeeId, row.name, row.dept, row.stageLabel, row.valueLabel, row.lastActive, row.views, row.interactions, row.posts]));
  }
  if (parsed.pathname === "/api/creators/ranking/export") {
    const data = await demoGetJson<{ creators: Array<Record<string, unknown>> }>("/api/creators/ranking", { ...params, limit: 1000, offset: 0 });
    return csv(["姓名", "部门", "发帖", "PV", "UV", "互动率", "读完率", "新增关注"], data.creators.map((row) => [row.name, row.dept, row.posts, row.pv, row.uv, row.interactionRate, row.completionRate, row.newFollowers]));
  }
  if (parsed.pathname === "/api/readers/lurkers/export") {
    const data = await demoGetJson<{ lurkers: Array<Record<string, unknown>> }>("/api/readers/lurkers", { ...params, limit: 1000, offset: 0 });
    return csv(["姓名", "部门", "加入日期", "浏览", "活跃天数", "最近活跃"], data.lurkers.map((row) => [row.name, row.dept, row.joinDate, row.views, row.activeDays, row.lastActive]));
  }
  throw new Error(`静态演示：不支持导出 ${parsed.pathname}`);
}
