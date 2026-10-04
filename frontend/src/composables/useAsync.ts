import { ref, shallowRef, type Ref } from "vue";

/**
 * 封装"加载中 / 数据 / 错误"。同一个实例连续发起多次请求时，只采用最后一次发起的请求的结果，
 * 先发后到的旧响应会被忽略。
 */
export function useAsync<A extends unknown[], T>(fn: (...args: A) => Promise<T>) {
  const data = shallowRef<T | null>(null) as Ref<T | null>;
  const loading = ref(false);
  const error = ref<string | null>(null);
  let latest = 0;

  async function run(...args: A): Promise<void> {
    const ticket = ++latest;
    loading.value = true;
    error.value = null;
    try {
      const result = await fn(...args);
      if (ticket === latest) {
        data.value = result;
      }
    } catch (e) {
      if (ticket === latest) {
        error.value = e instanceof Error ? e.message : String(e);
        data.value = null;
      }
    } finally {
      if (ticket === latest) {
        loading.value = false;
      }
    }
  }

  return { data, loading, error, run };
}
