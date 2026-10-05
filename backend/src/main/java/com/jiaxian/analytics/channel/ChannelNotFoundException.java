package com.jiaxian.analytics.channel;

/** 频道不存在（或不是综合/领域频道），对应 HTTP 404。 */
public class ChannelNotFoundException extends RuntimeException {

    public ChannelNotFoundException(long id) {
        super("Channel not found: " + id);
    }
}
