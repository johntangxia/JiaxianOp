package com.jiaxian.analytics.kol;

import java.time.LocalDate;

public record ActivityPoint(LocalDate periodStart, long posts, long comments, long logins, boolean complete) {
}
