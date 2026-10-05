package com.jiaxian.analytics.drill;

import java.time.LocalDate;
import java.util.List;

/** 频道下钻：在这个频道发过内容的账号（指标只统计该频道里的内容）。 */
public record ChannelAccounts(long channelId, String channelName, LocalDate from, LocalDate to, LocalDate previousFrom,
                              LocalDate previousTo, List<BreakdownRow> accounts) {
}
