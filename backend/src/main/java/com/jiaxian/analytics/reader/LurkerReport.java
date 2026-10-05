package com.jiaxian.analytics.reader;

import com.jiaxian.analytics.reader.ReaderQueryRepository.Lurker;

import java.time.LocalDate;
import java.util.List;

/** 沉默读者名单的一页；depts 是可用于筛选的部门列表。 */
public record LurkerReport(LocalDate from, LocalDate to, long total, int limit, int offset, List<Lurker> lurkers,
                           List<String> depts) {
}
