package com.jiaxian.analytics.simulator;

import java.util.Random;

/** 一次入口点击对应多少次"看到了但没点"的曝光：期望 (1 − CTR) / CTR，随机取整。 */
public final class ImpressionPlanner {

    private ImpressionPlanner() {
    }

    public static int unclickedImpressions(SourceKind kind, Random random) {
        double ctr = kind.ctr();
        double expected = (1 - ctr) / ctr;
        int whole = (int) Math.floor(expected);
        return whole + (random.nextDouble() < expected - whole ? 1 : 0);
    }
}
