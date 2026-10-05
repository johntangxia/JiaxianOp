package com.jiaxian.analytics.people;

import java.time.LocalDate;

/** 一个周期的创作者情况：发过帖的去重人数、历史上首次发帖落在本周期的人数、发帖数、人均发帖数。 */
public record CreatorPoint(LocalDate periodStart, long activeCreators, long newCreators, long posts, Double postsPerCreator) {
}
