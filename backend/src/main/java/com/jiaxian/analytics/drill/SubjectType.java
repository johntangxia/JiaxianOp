package com.jiaxian.analytics.drill;

/**
 * 分析对象的类型，以及它在 SQL 里的分组列。博主按内容的作者（content_author）归属，需要多连一张表，
 * 合著内容每位作者都会记一次。这些 SQL 片段是固定的，绝不拼接用户输入。
 */
public enum SubjectType {
    CHANNEL("c.channel_id"), ACCOUNT("c.account_id"), BLOGGER("ca.employee_id"), CONTENT("c.id");

    private final String keyColumn;

    SubjectType(String keyColumn) {
        this.keyColumn = keyColumn;
    }

    public String keyColumn() {
        return keyColumn;
    }
}
