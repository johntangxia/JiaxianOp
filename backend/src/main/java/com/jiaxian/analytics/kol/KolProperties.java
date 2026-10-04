package com.jiaxian.analytics.kol;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** analytics.kol.* 配置，修改后重启生效。 */
@Component
@ConfigurationProperties(prefix = "analytics.kol")
@Getter
@Setter
public class KolProperties {

    /** 名单人数上限。 */
    private int topN = 1000;
    /** R/F/M 的统计窗口天数。 */
    private int windowDays = 90;
    /** 流失预警的"近期"天数（同时也是成长趋势比较的天数）。 */
    private int recentDays = 30;
    /** 流失预警的基线天数（紧挨在近期之前）。 */
    private int baselineDays = 90;
    /** 近期日均相对基线日均下降超过这个比例就触发预警。 */
    private double churnDropThreshold = 0.5;
}
