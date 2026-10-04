import { describe, expect, it } from "vitest";
import { useAsync } from "./useAsync";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("useAsync", () => {
  it("tracks loading, data and errors", async () => {
    const ok = useAsync(async (n: number) => n * 2);
    const pending = ok.run(21);
    expect(ok.loading.value).toBe(true);
    await pending;
    expect(ok.loading.value).toBe(false);
    expect(ok.data.value).toBe(42);
    expect(ok.error.value).toBeNull();

    const failing = useAsync(async () => {
      throw new Error("boom");
    });
    await failing.run();
    expect(failing.error.value).toBe("boom");
    expect(failing.data.value).toBeNull();
    expect(failing.loading.value).toBe(false);
  });

  it("ignores a stale response that arrives after a newer request", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const queue = [first, second];
    const state = useAsync((_label: string) => queue.shift()!.promise);

    const run1 = state.run("old");
    const run2 = state.run("new");
    second.resolve("new result");
    await run2;
    first.resolve("old result");
    await run1;

    expect(state.data.value).toBe("new result");
    expect(state.loading.value).toBe(false);
  });

  it("clears a previous error when a new request starts", async () => {
    let fail = true;
    const state = useAsync(async () => {
      if (fail) {
        throw new Error("first fails");
      }
      return "fine";
    });

    await state.run();
    expect(state.error.value).toBe("first fails");
    fail = false;
    const pending = state.run();
    expect(state.error.value).toBeNull();
    await pending;
    expect(state.data.value).toBe("fine");
  });
});
