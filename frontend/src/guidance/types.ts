export type GuidanceId = string;
export interface GuidanceEntry {
  id: GuidanceId;
  title: string;
  meaning: string;
  calculation: string;
  analysis: string;
  caution: string;
  source: string;
  illustration?: string;
}
export interface RatioExampleInput {
  subject: string;
  from: string;
  to: string;
  numerator: number | null | undefined;
  denominator: number | null | undefined;
  numeratorLabel: string;
  denominatorLabel: string;
}
export interface GuidanceContext { example?: RatioExampleInput | (() => RatioExampleInput | null) }
export function entry(id: string, title: string, meaning: string, calculation: string, analysis: string, caution: string, source: string, illustration?: string): GuidanceEntry {
  return { id, title, meaning, calculation, analysis, caution, source, illustration };
}
