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
  const response = await fetch(path + buildQuery(params));
  if (!response.ok) {
    throw new ApiError(response.status, await readMessage(response));
  }
  return (await response.json()) as T;
}
