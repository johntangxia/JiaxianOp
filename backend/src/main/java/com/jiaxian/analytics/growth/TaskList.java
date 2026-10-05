package com.jiaxian.analytics.growth;

import java.time.LocalDate;
import java.util.List;

public record TaskList(long total, int limit, int offset, LocalDate asOf, List<TaskView> tasks) {
}
