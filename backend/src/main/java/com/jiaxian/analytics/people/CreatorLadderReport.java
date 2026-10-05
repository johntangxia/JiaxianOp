package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

/** 成长阶梯：kolDate 是用来判定 KOL 的那次重算日期（没有重算过为 null）。 */
public record CreatorLadderReport(LocalDate asOf, long members, LocalDate kolDate, List<LadderStageCount> stages,
                                  List<LadderReach> reach) {
}
