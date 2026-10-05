package com.jiaxian.analytics.growth;

import java.time.LocalDate;

public record AlertRebuildResult(LocalDate asOf, int created, int updated, int total) {
}
