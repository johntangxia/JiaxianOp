package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

/** 创作者产出集中度：头部贡献占比、基尼系数（按 PV），以及个人账号与团队账号的对比。 */
public record CreatorConcentrationReport(LocalDate from, LocalDate to, long creators, Double gini,
                                         List<ConcentrationShare> shares, List<AccountTypeRow> accountTypes) {
}
