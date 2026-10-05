package com.jiaxian.analytics.growth;

import com.jiaxian.analytics.reader.Member;
import com.jiaxian.analytics.reader.ReaderQueryRepository.MemberActivity;
import com.jiaxian.analytics.reader.ReaderRole;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 纯逻辑：部门渗透率。成员 = 窗口结束日已加入的员工；活跃 = 窗口内有任何行为记录；角色判定同读者分析。 */
public final class DepartmentPenetrationBuilder {

    public static final String OVERALL = "全社区";
    public static final String UNASSIGNED = "未分配部门";

    private DepartmentPenetrationBuilder() {
    }

    /**
     * @param current  当期窗口内每个员工的行为计数（没有行为的员工不在 map 里）
     * @param previous 上一个等长窗口的
     */
    public static DepartmentReport build(List<Member> members, Map<Long, MemberActivity> current,
                                         Map<Long, MemberActivity> previous, LocalDate from, LocalDate to,
                                         LocalDate previousFrom, LocalDate previousTo, int days) {
        Map<String, List<Member>> byDept = new LinkedHashMap<>();
        for (Member member : members) {
            byDept.computeIfAbsent(member.dept() == null ? UNASSIGNED : member.dept(), k -> new ArrayList<>()).add(member);
        }
        List<DepartmentRow> rows = new ArrayList<>();
        byDept.forEach((dept, list) -> rows.add(new DepartmentRow(dept,
            period(list, current, from, to), period(list, previous, previousFrom, previousTo))));
        rows.sort(Comparator.comparing((DepartmentRow r) -> r.current().penetration() == null ? -1.0 : r.current().penetration())
            .reversed().thenComparing(DepartmentRow::dept));
        DepartmentRow overall = new DepartmentRow(OVERALL, period(members, current, from, to),
            period(members, previous, previousFrom, previousTo));
        return new DepartmentReport(from, to, previousFrom, previousTo, days, rows, overall);
    }

    static DeptPeriod period(List<Member> members, Map<Long, MemberActivity> activity, LocalDate from, LocalDate to) {
        long total = 0;
        long active = 0;
        long creators = 0;
        long interactors = 0;
        long lurkers = 0;
        long newMembers = 0;
        for (Member member : members) {
            if (!member.isMemberOn(to)) {
                continue;
            }
            total++;
            if (member.joinDate() != null && !member.joinDate().isBefore(from)) {
                newMembers++;
            }
            MemberActivity a = activity.get(member.id());
            if (a == null || a.events() == 0) {
                continue;
            }
            active++;
            switch (ReaderRole.classify(a.posts(), a.interactions(), a.views(), true)) {
                case CREATOR -> creators++;
                case INTERACTOR -> interactors++;
                case LURKER -> lurkers++;
                default -> { }
            }
        }
        return new DeptPeriod(total, active, total == 0 ? null : active / (double) total, creators, interactors, lurkers,
            newMembers);
    }
}
