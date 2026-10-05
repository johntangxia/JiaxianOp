package com.jiaxian.analytics.people;

import java.time.LocalDate;
import java.util.List;

/** 兴趣与跨领域：按部门（= 领域）看成员的阅读去向。 */
public record UserInterestReport(LocalDate asOf, int days, List<DeptInterest> depts) {

    /** ownShare = 浏览本领域内容的占比；topOthers = 阅读最多的前 3 个其他领域及占浏览总数的比例。 */
    public record DeptInterest(String dept, long members, long readers, long views, Double ownShare, List<DomainShare> topOthers) {
    }

    public record DomainShare(String domain, long views, double share) {
    }
}
