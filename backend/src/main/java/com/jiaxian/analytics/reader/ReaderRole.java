package com.jiaxian.analytics.reader;

/** 读者角色，判定顺序固定、一个人只属于一类：创作者 > 互动者 > 沉默读者 > 仅登录 > 不活跃。 */
public enum ReaderRole {
    CREATOR("创作者"), INTERACTOR("互动者"), LURKER("沉默读者"), LOGIN_ONLY("仅登录"), INACTIVE("不活跃");

    private final String label;

    ReaderRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param posts        周期内发帖数
     * @param interactions 周期内点赞、评论、转发、收藏数
     * @param views        周期内浏览数
     * @param anyActivity  周期内是否有任何行为记录（含登录）
     */
    public static ReaderRole classify(long posts, long interactions, long views, boolean anyActivity) {
        if (posts > 0) {
            return CREATOR;
        }
        if (interactions > 0) {
            return INTERACTOR;
        }
        if (views > 0) {
            return LURKER;
        }
        return anyActivity ? LOGIN_ONLY : INACTIVE;
    }
}
