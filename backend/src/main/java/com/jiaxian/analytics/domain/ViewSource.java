package com.jiaxian.analytics.domain;

/** 一次浏览的来源：ENTRY 从主页的频道入口点进来，SEARCH 搜索，SHARE 转发链接，OTHER 其他未归因（推送、直接打开等）。 */
public enum ViewSource {
    ENTRY, SEARCH, SHARE, OTHER
}
