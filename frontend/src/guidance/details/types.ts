export interface GuidanceDetail {
  reading: string[];
  analysis: string[];
  value: string[];
  related?: string[];
}
export function detail(reading: string[], analysis: string[], value: string[], related?: string[]): GuidanceDetail {
  return { reading, analysis, value, related };
}
