package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

public record CreatorRankingReport(LocalDate from, LocalDate to, String sort, long total, int limit, int offset,
                                   List<CreatorRow> creators, List<String> depts) {
}
