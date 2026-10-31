import { describe, expect, it } from "vitest";
import { addDemoTask, changeDemoTaskStatus, deleteDemoTask, readDemoTasks } from "./tasks";

function memoryStorage() {
  const items = new Map<string, string>();
  return {
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => { items.set(key, value); },
  };
}

describe("browser-local growth tasks", () => {
  it("creates, updates and removes tasks only in the provided browser store", () => {
    const store = memoryStorage();
    const before = readDemoTasks(store);
    const added = addDemoTask(store, { title: "提升频道自主分发", entityType: "CHANNEL", entityName: "AI研发", metric: "OWN_ENTRY_SHARE", targetValue: 0.1, owner: "演示运营" });
    expect(readDemoTasks(store)).toHaveLength(before.length + 1);
    expect(readDemoTasks(store).find((task) => task.id === added.id)?.status).toBe("TODO");
    changeDemoTaskStatus(store, added.id, "DOING");
    expect(readDemoTasks(store).find((task) => task.id === added.id)?.status).toBe("DOING");
    deleteDemoTask(store, added.id);
    expect(readDemoTasks(store).some((task) => task.id === added.id)).toBe(false);
  });

  it("recovers from malformed local data", () => {
    const store = memoryStorage();
    store.setItem("jiaxian-static-demo-tasks-v1", "not json");
    expect(readDemoTasks(store).length).toBeGreaterThan(0);
  });
});
