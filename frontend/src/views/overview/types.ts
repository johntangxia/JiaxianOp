import type { ContentType, Granularity, PvFilter } from "../../api/types";
import type { DateRange } from "../../utils/dateRange";

/** 总览看板的整套查询条件（趋势图和 Top N 共用）。 */
export interface OverviewQuery {
  granularity: Granularity;
  range: DateRange;
  channelId?: number;
  accountId?: number;
  employeeId?: number;
  contentType?: ContentType;
  forecastPeriods: number;
}

export function filterOf(query: OverviewQuery): PvFilter {
  return {
    channelId: query.channelId,
    accountId: query.accountId,
    employeeId: query.employeeId,
    contentType: query.contentType,
  };
}
