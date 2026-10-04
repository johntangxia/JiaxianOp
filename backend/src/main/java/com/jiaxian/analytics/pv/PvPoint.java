package com.jiaxian.analytics.pv;

import java.time.LocalDate;

/** 趋势接口返回的一个点。momRate/yoyRate 为小数（0.05 即 +5%），无法计算时为 null。 */
public record PvPoint(LocalDate periodStart, long pv, Double momRate, Double yoyRate, boolean complete) {
}
