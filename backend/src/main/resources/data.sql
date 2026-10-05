INSERT INTO channel (name, category) VALUES
    ('关注', '综合频道'),
    ('推荐', '综合频道'),
    ('热点', '综合频道'),
    ('视频', '综合频道'),
    ('直播', '综合频道'),
    ('AI学习', '领域频道'),
    ('终端', '领域频道'),
    ('智能汽车', '领域频道'),
    ('半导体', '领域频道'),
    ('无线', '领域频道'),
    ('云核', '领域频道'),
    ('AI研发', '领域频道'),
    ('自动驾驶网络ADN', '领域频道'),
    ('交付服务', '领域频道'),
    ('服务软件', '领域频道'),
    ('华为云', '领域频道'),
    ('光', '领域频道'),
    ('数通', '领域频道'),
    ('数据存储', '领域频道'),
    ('计算', '领域频道'),
    ('知识产权', '领域频道'),
    ('制造', '领域频道'),
    ('供应链', '领域频道'),
    ('战略', '领域频道'),
    ('能源', '领域频道'),
    ('质量', '领域频道'),
    ('海外研究所', '领域频道'),
    ('2012', '领域频道')
ON DUPLICATE KEY UPDATE category = category;

-- 旧库升级：旧种子里的"专业领域"和"AI学习"（曾被放在综合频道）统一改成"领域频道"；
-- 旧种子里已经不存在的频道，没有内容引用就删掉，有引用就停用（下次历史回填会清掉）。幂等。
DELETE FROM channel
 WHERE name IN ('移动无线', '固定网络', 'IT计算')
   AND NOT EXISTS (SELECT 1 FROM content WHERE content.channel_id = channel.id);
UPDATE channel SET category = '已停用' WHERE name IN ('移动无线', '固定网络', 'IT计算');
UPDATE channel SET category = '领域频道' WHERE category = '专业领域';
UPDATE channel SET category = '领域频道' WHERE name = 'AI学习';

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
