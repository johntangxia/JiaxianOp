import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson } from "./http";

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
