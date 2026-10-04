package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.ContentType;

/** 可选过滤条件，全部为 null 表示不过滤；多个条件取交集。employeeId 指"该员工是内容的作者之一"。 */
public record PvFilter(Long channelId, Long accountId, Long employeeId, ContentType contentType) {

    public static final PvFilter NONE = new PvFilter(null, null, null, null);
}
