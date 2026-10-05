package com.jiaxian.analytics.channel;

/** 自动诊断出的一个问题。severity：CRITICAL 严重、WARN 警告、INFO 提示。 */
public record Diagnosis(String code, String severity, String message) {

    public static final String CRITICAL = "CRITICAL";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";
}
