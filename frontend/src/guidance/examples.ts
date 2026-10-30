import type { RatioExampleInput } from "./types";
import { formatPercent } from "../utils/channelMetrics";
export type RatioScope = Pick<RatioExampleInput, "subject" | "from" | "to">;
export function ratioExample(input: RatioExampleInput, scope: RatioScope): string | null {
  const { numerator, denominator, subject, from, to, numeratorLabel, denominatorLabel } = input;
  if (!subject || !from || !to || subject !== scope.subject || from !== scope.from || to !== scope.to ||
      typeof numerator !== "number" || typeof denominator !== "number" ||
      !Number.isFinite(numerator) || !Number.isFinite(denominator) || numerator < 0 || denominator <= 0) return null;
  return `${subject}（${from} 至 ${to}）：${numeratorLabel} ${numerator.toLocaleString()} ÷ ${denominatorLabel} ${denominator.toLocaleString()} ≈ ${formatPercent(numerator / denominator)}`;
}
