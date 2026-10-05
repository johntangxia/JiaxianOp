package com.jiaxian.analytics.people;

import com.jiaxian.analytics.pv.Granularity;
import com.jiaxian.analytics.reader.ActiveDay;
import com.jiaxian.analytics.reader.CohortBuilder;
import com.jiaxian.analytics.reader.CohortRow;
import com.jiaxian.analytics.reader.Member;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：创作者留存同期群。把"首次发帖日"当作加入日、"发帖日"当作活跃日，复用读者分析的同期群算法：
 * 按首次发帖月分组，第 k 列 = 之后第 k 个月里至少又发过一次帖的人占比。
 */
public final class CreatorRetention {

    private CreatorRetention() {
    }

    public static List<CohortRow> build(Map<Long, LocalDate> firstPost, List<PostDay> postDays, LocalDate from, LocalDate to,
                                        int periods, LocalDate dataEnd) {
        List<Member> creators = new ArrayList<>();
        firstPost.forEach((id, day) -> creators.add(new Member(id, "", null, day)));
        List<ActiveDay> days = postDays.stream().map(d -> new ActiveDay(d.employeeId(), d.day())).toList();
        return CohortBuilder.build(creators, days, from, to, Granularity.MONTH, periods, dataEnd);
    }
}
