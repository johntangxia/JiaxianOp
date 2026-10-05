package com.jiaxian.analytics.reader;

/** 一类读者角色的人数和占成员总数的比例（没有成员时为 null）。 */
public record SegmentCount(ReaderRole role, String label, long count, Double share) {
}
