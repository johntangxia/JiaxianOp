import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson, sendJson } from "./http";

function stubFetch(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("getJson", () => {
  it("builds the query string and returns the parsed body", async () => {
    const fetchMock = stubFetch(new Response(JSON.stringify({ ok: 1 }), { status: 200 }));

    const result = await getJson<{ ok: number }>("/api/x", { a: 1, b: undefined });

    expect(result).toEqual({ ok: 1 });
    expect(fetchMock).toHaveBeenCalledWith("/api/x?a=1");
  });

  it("turns a 4xx text body into an ApiError with that message", async () => {
    stubFetch(new Response("from must not be after to", { status: 400 }));

    await expect(getJson("/api/x")).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      message: "from must not be after to",
    });
  });

  it("uses a default message for an empty 4xx body", async () => {
    stubFetch(new Response("", { status: 404 }));

    await expect(getJson("/api/x")).rejects.toMatchObject({ status: 404, message: "请求失败（404）" });
  });

  it("hides the body of 5xx responses behind a generic message", async () => {
    stubFetch(new Response('{"trace":"secret internals"}', { status: 500 }));

    const error = (await getJson("/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe("服务器内部错误（500）");
    expect(error.message).not.toContain("secret");
  });
});

describe("sendJson", () => {
  it("sends a JSON body with the right header and parses a JSON response", async () => {
    const fetchMock = stubFetch(new Response(JSON.stringify({ id: 7 }), { status: 200 }));

    const result = await sendJson<{ id: number }>("PUT", "/api/x/LIKE", { params: { a: 1 }, body: { weight: 4 } });

    expect(result).toEqual({ id: 7 });
    expect(fetchMock).toHaveBeenCalledWith("/api/x/LIKE?a=1", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: '{"weight":4}',
    });
  });

  it("returns a plain-text response as a string", async () => {
    stubFetch(new Response("Backfill completed for batch: demo", { status: 200 }));

    expect(await sendJson<string>("POST", "/api/admin/simulator/backfill", { body: {} })).toBe(
      "Backfill completed for batch: demo",
    );
  });

  it("returns undefined for an empty response and sends no body for DELETE", async () => {
    const fetchMock = stubFetch(new Response(null, { status: 204 }));

    expect(await sendJson<void>("DELETE", "/api/x/versions/3")).toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith("/api/x/versions/3", { method: "DELETE" });
  });

  it("turns error responses into an ApiError", async () => {
    stubFetch(new Response("A playbook entry for STAR_KOL / NO_ALERT already exists", { status: 409 }));

    await expect(sendJson("POST", "/api/admin/playbook", { body: {} })).rejects.toMatchObject({
      status: 409,
      message: "A playbook entry for STAR_KOL / NO_ALERT already exists",
    });

    stubFetch(new Response("boom", { status: 502 }));
    await expect(sendJson("POST", "/api/x")).rejects.toMatchObject({ message: "服务器内部错误（502）" });
  });
});
