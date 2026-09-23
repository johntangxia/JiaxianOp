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
