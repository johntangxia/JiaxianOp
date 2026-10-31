import { buildQuery, type QueryParams } from "./query";

/** 后端返回的错误：4xx 的 message 就是后端那句文字说明，5xx 只给通用提示。 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function readMessage(response: Response): Promise<string> {
  if (response.status >= 500) {
    return `服务器内部错误（${response.status}）`;
  }
  const text = (await response.text()).trim();
  return text || `请求失败（${response.status}）`;
}

export async function getJson<T>(path: string, params?: QueryParams): Promise<T> {
  if (import.meta.env.VITE_STATIC_DEMO === "1") {
    const { demoGetJson } = await import("../demo/api");
    return demoGetJson<T>(path, params);
  }
  const response = await fetch(path + buildQuery(params));
  if (!response.ok) {
    throw new ApiError(response.status, await readMessage(response));
  }
  return (await response.json()) as T;
}

export interface SendOptions {
  params?: QueryParams;
  body?: unknown;
}

/** 响应是 JSON 就解析；是纯文本就原样返回字符串；空响应返回 undefined。 */
async function parseBody<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return text as unknown as T;
  }
}

/** POST / PUT / DELETE：有 body 时按 JSON 发送；错误处理与 getJson 一致。 */
export async function sendJson<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  options: SendOptions = {},
): Promise<T> {
  if (import.meta.env.VITE_STATIC_DEMO === "1") {
    const { demoSendJson } = await import("../demo/api");
    return demoSendJson<T>(method, path, options);
  }
  const init: RequestInit = { method };
  if (options.body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path + buildQuery(options.params), init);
  if (!response.ok) {
    throw new ApiError(response.status, await readMessage(response));
  }
  return parseBody<T>(response);
}
