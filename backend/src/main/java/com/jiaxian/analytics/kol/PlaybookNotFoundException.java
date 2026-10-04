package com.jiaxian.analytics.kol;

/** 建议文案不存在，对应 HTTP 404。 */
public class PlaybookNotFoundException extends RuntimeException {

    public PlaybookNotFoundException(long id) {
        super("Playbook entry " + id + " does not exist");
    }
}
