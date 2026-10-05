package com.jiaxian.analytics.channel;

/** 构成图的一块：领域频道是来源占比，综合频道是点击内容所属领域占比。 */
public record MixSlice(String key, String label, long value) {
}
