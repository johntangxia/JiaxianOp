package com.jiaxian.analytics.growth;

/** 告警或任务不存在，对应 HTTP 404。 */
public class GrowthNotFoundException extends RuntimeException {

    public GrowthNotFoundException(String kind, long id) {
        super(kind + " not found: " + id);
    }
}
