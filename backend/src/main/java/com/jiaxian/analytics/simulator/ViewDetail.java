package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ViewSource;

/** 一次浏览除"谁、看了什么、什么时候"之外的细节；全为空表示没有细节（旧的调用方式）。 */
public record ViewDetail(ViewSource source, Long entryChannelId, Integer dwellSeconds, Boolean completed) {

    public static final ViewDetail NONE = new ViewDetail(null, null, null, null);
}
