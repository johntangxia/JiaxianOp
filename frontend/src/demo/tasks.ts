import type { GrowthCreateTaskBody, GrowthTask, GrowthTaskStatus, GrowthUpdateTaskBody } from "../api/types";
import { demoData } from "./data";

const KEY = "jiaxian-static-demo-tasks-v1";
type Store = Pick<Storage, "getItem" | "setItem">;
const memory = new Map<string, string>();
const memoryStore: Store = { getItem: (key) => memory.get(key) ?? null, setItem: (key, value) => { memory.set(key, value); } };

function defaultStore(): Store {
  if (typeof window === "undefined") return memoryStore;
  try { return window.localStorage ?? memoryStore; } catch { return memoryStore; }
}

export interface DemoTask extends GrowthTask { currentValue: number | null }

function seedTasks(): DemoTask[] {
  const day = demoData.snapshotDate;
  return [
    { id: 1, title: "提升 AI研发 自主分发", taskType: "IMPROVE_OWN_ENTRY", entityType: "CHANNEL", entityId: 12, entityName: "AI研发", metric: "OWN_ENTRY_SHARE", baselineValue: 0, currentValue: 0.03, targetValue: 0.12, ownerName: "演示运营", startDate: day, dueDate: null, status: "DOING", note: "浏览器本地演示任务", alertId: null, createdAt: day, updatedAt: day },
    { id: 2, title: "优化推荐入口点击率", taskType: "OTHER", entityType: "CHANNEL", entityId: 2, entityName: "推荐", metric: "OWN_CTR", baselineValue: 0.04, currentValue: 0.04, targetValue: 0.06, ownerName: "演示运营", startDate: day, dueDate: null, status: "TODO", note: "浏览器本地演示任务", alertId: null, createdAt: day, updatedAt: day },
  ];
}

export function readDemoTasks(store: Store = defaultStore()): DemoTask[] {
  const raw = store.getItem(KEY);
  if (raw === null) return seedTasks();
  try {
    const value: unknown = JSON.parse(raw);
    return Array.isArray(value) && value.every((row) => row && typeof row.id === "number" && typeof row.title === "string") ? value as DemoTask[] : seedTasks();
  } catch { return seedTasks(); }
}

function save(store: Store, tasks: DemoTask[]): void { store.setItem(KEY, JSON.stringify(tasks)); }

export function addDemoTask(store: Store = defaultStore(), body: GrowthCreateTaskBody): DemoTask {
  const tasks = readDemoTasks(store);
  const day = demoData.snapshotDate;
  const task: DemoTask = {
    id: Math.max(0, ...tasks.map((item) => item.id)) + 1,
    title: body.title,
    taskType: body.taskType ?? "OTHER",
    entityType: body.entityType,
    entityId: body.entityId ?? null,
    entityName: body.entityName ?? null,
    metric: body.metric,
    baselineValue: null,
    currentValue: null,
    targetValue: body.targetValue,
    ownerName: body.owner ?? null,
    startDate: body.startDate ?? day,
    dueDate: body.dueDate ?? null,
    status: "TODO",
    note: body.note ?? null,
    alertId: body.alertId ?? null,
    createdAt: day,
    updatedAt: day,
  };
  save(store, [...tasks, task]);
  return task;
}

export function changeDemoTaskStatus(store: Store = defaultStore(), id: number, status: GrowthTaskStatus): DemoTask | undefined {
  const tasks = readDemoTasks(store);
  const task = tasks.find((item) => item.id === id);
  if (!task) return undefined;
  task.status = status;
  task.updatedAt = demoData.snapshotDate;
  save(store, tasks);
  return task;
}

export function updateDemoTask(store: Store = defaultStore(), id: number, body: GrowthUpdateTaskBody): DemoTask | undefined {
  const tasks = readDemoTasks(store);
  const task = tasks.find((item) => item.id === id);
  if (!task) return undefined;
  Object.assign(task, body);
  if (body.owner !== undefined) task.ownerName = body.owner;
  task.updatedAt = demoData.snapshotDate;
  save(store, tasks);
  return task;
}

export function deleteDemoTask(store: Store = defaultStore(), id: number): void {
  save(store, readDemoTasks(store).filter((item) => item.id !== id));
}
