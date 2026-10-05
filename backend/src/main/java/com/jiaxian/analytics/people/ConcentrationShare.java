package com.jiaxian.analytics.people;

/** 头部创作者的贡献：前 N 名（或前 x%）创作者占全部 PV 和发帖的比例；总数为 0 时比例为 null。 */
public record ConcentrationShare(String label, int creators, Double pvShare, Double postShare) {
}
