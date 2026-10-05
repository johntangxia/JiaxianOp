package com.jiaxian.analytics.growth;

import java.time.LocalDate;
import java.util.List;

public record HotContentReport(LocalDate asOf, List<HotContentRow> hot, List<HotContentRow> potential) {
}
