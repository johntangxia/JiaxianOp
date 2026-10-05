package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ContentType;

import java.util.Random;

/** 抽样停留时长和是否读完/播完：对数正态分布，读完/播完的停留放大 1.6 倍。 */
public final class ViewDetailSampler {

    static final double ARTICLE_MEDIAN_SECONDS = 60;
    static final double VIDEO_MEDIAN_SECONDS = 80;
    static final double ARTICLE_SIGMA = 0.6;
    static final double VIDEO_SIGMA = 0.7;
    static final int ARTICLE_MAX_SECONDS = 600;
    static final int VIDEO_MAX_SECONDS = 900;
    static final int MIN_SECONDS = 3;
    static final double ARTICLE_COMPLETION = 0.55;
    static final double VIDEO_COMPLETION = 0.35;
    static final double COMPLETION_BOOST = 1.6;

    private ViewDetailSampler() {
    }

    public record Dwell(int seconds, boolean completed) {
    }

    public static Dwell sample(ContentType type, Random random) {
        boolean video = type == ContentType.VIDEO;
        double median = video ? VIDEO_MEDIAN_SECONDS : ARTICLE_MEDIAN_SECONDS;
        double sigma = video ? VIDEO_SIGMA : ARTICLE_SIGMA;
        int max = video ? VIDEO_MAX_SECONDS : ARTICLE_MAX_SECONDS;
        boolean completed = random.nextDouble() < (video ? VIDEO_COMPLETION : ARTICLE_COMPLETION);

        double seconds = median * Math.exp(sigma * random.nextGaussian());
        if (completed) {
            seconds *= COMPLETION_BOOST;
        }
        return new Dwell((int) Math.max(MIN_SECONDS, Math.min(max, Math.round(seconds))), completed);
    }
}
