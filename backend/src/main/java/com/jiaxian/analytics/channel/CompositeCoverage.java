package com.jiaxian.analytics.channel;

/** 综合频道没有自己的内容，改看入口点击覆盖了多少个领域，以及点击最多的领域占比。 */
public record CompositeCoverage(int domainsCovered, Double topDomainShare) {

    public static final CompositeCoverage EMPTY = new CompositeCoverage(0, null);
}
