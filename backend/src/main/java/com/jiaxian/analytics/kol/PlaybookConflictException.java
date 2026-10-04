package com.jiaxian.analytics.kol;

/** (分层, 预警状态) 已有文案，对应 HTTP 409。 */
public class PlaybookConflictException extends RuntimeException {

    public PlaybookConflictException(String message) {
        super(message);
    }
}
