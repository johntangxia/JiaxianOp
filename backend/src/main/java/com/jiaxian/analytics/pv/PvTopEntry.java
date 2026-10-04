package com.jiaxian.analytics.pv;

/** Top N 的一行。CONTENT_TYPE 维度的 id 为 null，name 为 ARTICLE / VIDEO。 */
public record PvTopEntry(Long id, String name, long pv) {
}
