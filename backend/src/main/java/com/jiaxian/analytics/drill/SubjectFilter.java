package com.jiaxian.analytics.drill;

import java.util.List;

/**
 * 查询的过滤条件，全部可选、可组合：只看某个频道/账号/作者的内容，或只看某几个分组键。
 * 例如"某个账号在各频道的分布" = 分组类型 CHANNEL + accountId 过滤。
 */
public record SubjectFilter(Long channelId, Long accountId, Long authorId, List<Long> ids) {

    public static final SubjectFilter NONE = new SubjectFilter(null, null, null, null);

    public static SubjectFilter ofIds(List<Long> ids) {
        return new SubjectFilter(null, null, null, ids);
    }

    public static SubjectFilter ofIds(long id) {
        return ofIds(List.of(id));
    }

    public SubjectFilter withChannel(long channelId) {
        return new SubjectFilter(channelId, accountId, authorId, ids);
    }

    public SubjectFilter withAccount(long accountId) {
        return new SubjectFilter(channelId, accountId, authorId, ids);
    }

    public SubjectFilter withAuthor(long authorId) {
        return new SubjectFilter(channelId, accountId, authorId, ids);
    }
}
