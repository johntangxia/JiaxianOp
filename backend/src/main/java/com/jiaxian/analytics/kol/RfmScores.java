package com.jiaxian.analytics.kol;

/** R/F/M 三个 1~5 分。 */
public record RfmScores(int r, int f, int m) {

    public double average() {
        return (r + f + m) / 3.0;
    }
}
