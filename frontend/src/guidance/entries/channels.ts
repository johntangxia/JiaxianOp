import { entry } from "../types";
const source = "backend/src/main/java/com/jiaxian/analytics/channel/ChannelQueryRepository.java";
export const channels = [
  entry("page.channels", "频道驾驶舱", "了解综合入口的分发和领域内容的表现。", "按所选日期分别汇总入口曝光、点击和领域内容 PV；环比使用上一个等长周期。", "先比较自主入口和综合入口，再查看来源、互动及诊断；点击频道进入详情。", "入口指标按展示入口统计，内容指标按内容所属领域统计。", source),
  entry("page.channel-detail", "频道详情", "查看频道的流量来源、分发和内容表现。", "指标按所选日期计算；综合频道按入口统计，领域频道还统计所属内容的全部来源。", "先看 PV 和来源，再看入口点击率、互动和诊断；领域频道可下钻账号。", "日、周、月只改变趋势的聚合粒度；当前数据为模拟数据。", source),
  entry("page.channel-composite", "综合频道详情", "查看一个综合入口展示和分发了哪些领域内容。", "按入口频道统计曝光、点击及触达；覆盖领域按有入口点击的内容所属领域统计。", "比较曝光、点击率和点击内容的领域分布，再进入相关领域。", "综合频道是分发入口，不拥有领域内容。", source),
  entry("metric.entryExposure", "入口曝光", "内容在指定频道入口的信息流中展示的次数。", "统计日期范围内入口频道对应的曝光记录，点击和未点击都计入；同一人多次看到计多次。", "结合点击与触达人数判断入口的分发规模。", "曝光不是去重人数；模拟数据并非真实埋点。", source),
  entry("metric.entryCtr", "入口点击率", "指定频道入口曝光后获得点击的比例。", "该入口的点击曝光次数 ÷ 该入口全部曝光次数；没有曝光时为空。", "结合曝光规模、同类入口及日期范围分析。", "只看该入口，不代表所属内容在其他入口的整体点击率。", source),
  entry("metric.contentCtr", "内容点击率", "所选对象的内容在各频道入口展示后被点击的比例。", "属于该对象、且符合当前筛选的内容入口点击次数 ÷ 这些内容在各入口的曝光次数。", "结合曝光量比较同类内容；在频道下的账号表中只统计该频道的内容。", "搜索、分享等浏览不计入入口曝光点击；无曝光时为空。", "backend/src/main/java/com/jiaxian/analytics/drill/SubjectQueryRepository.java", "示意：200 次曝光、12 次点击，点击率为 12 ÷ 200 = 6.0%。"),
  entry("metric.interaction", "互动率", "所选领域内容每次浏览对应的互动次数。", "期间内内容获得的点赞、转发、收藏和评论次数 ÷ 内容 PV；评论点赞不计入。", "结合 PV、各项互动及内容类型观察变化。", "按次数而不是人数统计；互动与浏览分别按发生日期汇总，并非逐次配对。", "backend/src/main/java/com/jiaxian/analytics/channel/ContentMetricsFactory.java", "示意：100 次浏览、3 次互动，互动率为 3 ÷ 100 = 3.0%。"),
  entry("metric.ownShare", "自主分发占比", "领域内容浏览中来自该领域自己的入口的比例。", "自主入口 PV ÷ 该领域内容的全部 PV；无 PV 时为空。", "结合综合依赖度及来源构成，观察领域入口带来的流量。", "它衡量内容浏览来源，并非自主入口点击率。", source),
  entry("metric.compositeDependency", "综合依赖度", "领域内容浏览中来自综合频道入口的比例。", "综合入口 PV ÷ 领域内容的全部 PV；无 PV 时为空。", "结合自主入口、搜索与分享来源分析，不必将比例高低单独视为好坏。", "这是来源比例，不是依赖的因果证明。", source),
  entry("metric.concentration", "头部集中度", "内容浏览是否集中在少数作品上。", "对比表显示期间 PV 最高的一篇内容 PV ÷ 所属对象的总 PV。", "结合前三篇占比、内容供给与时间变化观察集中风险。", "内容可在统计期之前发布；这不是创作者 PV 的基尼系数。", "backend/src/main/java/com/jiaxian/analytics/channel/ContentMetricsFactory.java"),
];
