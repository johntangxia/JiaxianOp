INSERT INTO channel (name, category) VALUES
    ('关注', '综合频道'),
    ('推荐', '综合频道'),
    ('热点', '综合频道'),
    ('视频', '综合频道'),
    ('直播', '综合频道'),
    ('AI学习', '综合频道'),
    ('终端', '专业领域'),
    ('移动无线', '专业领域'),
    ('固定网络', '专业领域'),
    ('半导体', '专业领域'),
    ('IT计算', '专业领域'),
    ('智能汽车', '专业领域'),
    ('知识产权', '专业领域'),
    ('制造', '专业领域'),
    ('供应链', '专业领域')
ON DUPLICATE KEY UPDATE category = category;

INSERT INTO points_weight_config (action_type, category, weight, effective_date) VALUES
    ('POST', 'ACTIVE', 100, '2026-01-01'),
    ('LOGIN', 'ACTIVE', 20, '2026-01-01'),
    ('COMMENT', 'ACTIVE', 15, '2026-01-01'),
    ('SHARE', 'ACTIVE', 15, '2026-01-01'),
    ('FAVORITE', 'ACTIVE', 5, '2026-01-01'),
    ('LIKE', 'ACTIVE', 3, '2026-01-01'),
    ('VIEW', 'ACTIVE', 1, '2026-01-01'),
    ('EDITORIAL_PICK', 'PASSIVE', 300, '2026-01-01'),
    ('CONTENT_COMMENTED', 'PASSIVE', 15, '2026-01-01'),
    ('CONTENT_SHARED', 'PASSIVE', 15, '2026-01-01'),
    ('COMMENT_REPLIED', 'PASSIVE', 15, '2026-01-01'),
    ('CONTENT_FAVORITED', 'PASSIVE', 5, '2026-01-01'),
    ('CONTENT_LIKED', 'PASSIVE', 3, '2026-01-01'),
    ('COMMENT_LIKED', 'PASSIVE', 3, '2026-01-01'),
    ('CONTENT_VIEWED', 'PASSIVE', 1, '2026-01-01')
ON DUPLICATE KEY UPDATE weight = weight;

INSERT INTO action_playbook (segment_label, alert_status, recommendation_text) VALUES
    ('STAR_KOL', 'NO_ALERT', '纳入“专家专栏”资源位重点推荐，邀请参与线下分享/评审活动，保持曝光激励'),
    ('STEADY', 'NO_ALERT', '保持常规运营：定期推送热门话题与约稿机会，鼓励向明星KOL梯队成长'),
    ('RISING', 'NO_ALERT', '加入新人扶持计划，给予首页曝光加权，缩短内容从发布到获得反馈的周期'),
    ('CHURN_RISK', 'ALERT', '运营同学私信关怀，了解活跃下降原因，推荐开放专属专栏权限或约稿邀请'),
    ('DORMANT', 'NO_ALERT', '纳入低成本触达清单（邮件/群通知召回），不做重点人工投入'),
    ('OBSERVE', 'NO_ALERT', '暂不做专项运营，持续观察下一周期的分层变化')
ON DUPLICATE KEY UPDATE recommendation_text = recommendation_text;
