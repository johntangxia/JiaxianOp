package com.jiaxian.analytics.growth;

/** 一篇内容在一个窗口里的表现：PV、前一个等长窗口的 PV、互动数和读完率（没有数据为 null）。 */
public record ContentStat(long contentId, long channelId, long pv, long previousPv, long interactions,
                          Double completionRate) {

    Double interactionRate() {
        return pv == 0 ? null : interactions / (double) pv;
    }
}
