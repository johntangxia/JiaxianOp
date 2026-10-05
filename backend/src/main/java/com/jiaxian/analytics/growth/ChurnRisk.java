package com.jiaxian.analytics.growth;

/** 最近一次 KOL 重算里被判为流失预警的博主；rank 是他在 KOL 名单里的名次。 */
public record ChurnRisk(long employeeId, String name, int rank) {
}
