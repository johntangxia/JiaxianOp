package com.jiaxian.analytics.simulator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** 内容标签词表和搜索关键词：每篇内容 = 领域名 + 2–3 个共享标签（按幂律挑，靠前的标签更常用）。 */
public final class TagCatalog {

    public static final List<String> SHARED_TAGS = List.of(
        "架构设计", "性能优化", "测试", "需求分析", "项目管理", "行业动态", "新员工指南", "降本增效",
        "专利", "标准", "质量", "安全", "交付", "方法论", "复盘", "AI应用");

    static final double TAG_SKEW = 1.0;
    static final double KEYWORD_FROM_CONTENT_TAG = 0.60;
    static final double KEYWORD_FROM_DOMAIN = 0.25;

    private TagCatalog() {
    }

    /** 领域名标签排第一，后面是 2–3 个不重复的共享标签。 */
    public static List<String> tagsFor(String domainName, Random random) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(domainName);
        int extra = 2 + random.nextInt(2);
        while (tags.size() < 1 + extra) {
            tags.add(SHARED_TAGS.get(zipfIndex(SHARED_TAGS.size(), random)));
        }
        return new ArrayList<>(tags);
    }

    /** 60% 取内容自己的标签，25% 取内容所属领域名，其余从词表里任取一个。 */
    public static String keyword(List<String> contentTags, String domainName, Random random) {
        double roll = random.nextDouble();
        if (roll < KEYWORD_FROM_CONTENT_TAG && !contentTags.isEmpty()) {
            return contentTags.get(random.nextInt(contentTags.size()));
        }
        if (roll < KEYWORD_FROM_CONTENT_TAG + KEYWORD_FROM_DOMAIN) {
            return domainName;
        }
        return SHARED_TAGS.get(random.nextInt(SHARED_TAGS.size()));
    }

    private static int zipfIndex(int size, Random random) {
        double total = 0;
        for (int i = 0; i < size; i++) {
            total += 1.0 / Math.pow(i + 1, TAG_SKEW);
        }
        double target = random.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < size; i++) {
            cumulative += 1.0 / Math.pow(i + 1, TAG_SKEW);
            if (target < cumulative) {
                return i;
            }
        }
        return size - 1;
    }
}
