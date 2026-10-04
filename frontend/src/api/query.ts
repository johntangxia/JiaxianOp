export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** 把参数对象转成 "?a=1&b=x"，跳过 null、undefined 和空字符串；没有可用参数时返回空字符串。 */
export function buildQuery(params: QueryParams = {}): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") {
      continue;
    }
    search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}
