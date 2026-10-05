CREATE TABLE IF NOT EXISTS employee (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    dept                VARCHAR(100),
    join_date           DATE,
    community_join_date DATE,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS channel (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS account (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('PERSONAL', 'TEAM')),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS account_member (
    id                  BIGSERIAL PRIMARY KEY,
    account_id          BIGINT NOT NULL REFERENCES account(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS content (
    id                  BIGSERIAL PRIMARY KEY,
    type                VARCHAR(20) NOT NULL CHECK (type IN ('ARTICLE', 'VIDEO')),
    channel_id          BIGINT NOT NULL REFERENCES channel(id),
    account_id          BIGINT NOT NULL REFERENCES account(id),
    publish_time        TIMESTAMP NOT NULL,
    popularity_weight   DOUBLE PRECISION NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS content_author (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS comment (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    parent_comment_id   BIGINT REFERENCES comment(id),
    create_time         TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_account_member_account ON account_member(account_id);
CREATE INDEX IF NOT EXISTS idx_account_member_employee ON account_member(employee_id);
CREATE INDEX IF NOT EXISTS idx_content_channel ON content(channel_id);
CREATE INDEX IF NOT EXISTS idx_content_account ON content(account_id);
CREATE INDEX IF NOT EXISTS idx_content_author_content ON content_author(content_id);
CREATE INDEX IF NOT EXISTS idx_content_author_employee ON content_author(employee_id);
CREATE INDEX IF NOT EXISTS idx_comment_content ON comment(content_id);
CREATE INDEX IF NOT EXISTS idx_comment_parent ON comment(parent_comment_id);

CREATE TABLE IF NOT EXISTS pv_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    viewer_employee_id  BIGINT NOT NULL REFERENCES employee(id),
    view_time           TIMESTAMP NOT NULL,
    entry_channel_id    BIGINT REFERENCES channel(id),
    source_type         VARCHAR(10),
    dwell_seconds       INTEGER,
    completed           BOOLEAN,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS engagement_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT REFERENCES content(id),
    comment_id          BIGINT REFERENCES comment(id),
    actor_employee_id   BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(20) NOT NULL CHECK (action_type IN ('LIKE', 'SHARE', 'FAVORITE')),
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64),
    CHECK (content_id IS NOT NULL OR comment_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS active_action_log (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(20) NOT NULL CHECK (action_type IN
        ('POST', 'COMMENT', 'LOGIN', 'SHARE', 'FAVORITE', 'LIKE', 'VIEW')),
    ref_id              BIGINT,
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS editorial_pick_log (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    pick_time           TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS points_weight_config (
    id              BIGSERIAL PRIMARY KEY,
    action_type     VARCHAR(30) NOT NULL,
    category        VARCHAR(10) NOT NULL CHECK (category IN ('ACTIVE', 'PASSIVE')),
    weight          INT NOT NULL,
    effective_date  DATE NOT NULL,
    UNIQUE (action_type, effective_date)
);

CREATE TABLE IF NOT EXISTS points_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    action_type         VARCHAR(30) NOT NULL,
    category            VARCHAR(10) NOT NULL CHECK (category IN ('ACTIVE', 'PASSIVE')),
    points              INT NOT NULL,
    ref_id              BIGINT,
    occur_time          TIMESTAMP NOT NULL,
    simulation_batch_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_pv_log_content ON pv_log(content_id);
CREATE INDEX IF NOT EXISTS idx_pv_log_view_time ON pv_log(view_time);
CREATE INDEX IF NOT EXISTS idx_engagement_log_content ON engagement_log(content_id);
CREATE INDEX IF NOT EXISTS idx_engagement_log_comment ON engagement_log(comment_id);
CREATE INDEX IF NOT EXISTS idx_active_action_log_employee ON active_action_log(employee_id);
CREATE INDEX IF NOT EXISTS idx_editorial_pick_content ON editorial_pick_log(content_id);
CREATE INDEX IF NOT EXISTS idx_points_ledger_employee_time ON points_ledger(employee_id, occur_time);

CREATE TABLE IF NOT EXISTS pv_summary_daily (
    content_id   BIGINT NOT NULL REFERENCES content(id) ON DELETE CASCADE,
    summary_date DATE NOT NULL,
    pv_count     BIGINT NOT NULL,
    PRIMARY KEY (content_id, summary_date)
);

CREATE TABLE IF NOT EXISTS leaderboard_snapshot (
    period_type    VARCHAR(10) NOT NULL CHECK (period_type IN ('MONTH', 'YEAR')),
    period_value   VARCHAR(10) NOT NULL,
    rank_no        INT NOT NULL,
    employee_id    BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    points         BIGINT NOT NULL,
    active_points  BIGINT NOT NULL,
    passive_points BIGINT NOT NULL,
    PRIMARY KEY (period_type, period_value, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_leaderboard_snapshot_rank
    ON leaderboard_snapshot(period_type, period_value, rank_no);

CREATE TABLE IF NOT EXISTS blogger_rfm_score (
    as_of_date         DATE NOT NULL,
    employee_id        BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    rank_no            INT NOT NULL,
    rolling_points_12m BIGINT NOT NULL,
    recency_days       INT,
    frequency          INT NOT NULL,
    monetary           BIGINT NOT NULL,
    r_score            INT NOT NULL,
    f_score            INT NOT NULL,
    m_score            INT NOT NULL,
    segment_label      VARCHAR(20) NOT NULL,
    churn_alert        BOOLEAN NOT NULL,
    PRIMARY KEY (as_of_date, employee_id)
);

CREATE TABLE IF NOT EXISTS churn_alert (
    alert_date      DATE NOT NULL,
    employee_id     BIGINT NOT NULL REFERENCES employee(id) ON DELETE CASCADE,
    metric          VARCHAR(10) NOT NULL CHECK (metric IN ('ACTIVITY', 'POINTS')),
    recent_daily    DOUBLE PRECISION NOT NULL,
    baseline_daily  DOUBLE PRECISION NOT NULL,
    drop_rate       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (alert_date, employee_id)
);

CREATE TABLE IF NOT EXISTS action_playbook (
    id                  BIGSERIAL PRIMARY KEY,
    segment_label       VARCHAR(20) NOT NULL,
    alert_status        VARCHAR(10) NOT NULL CHECK (alert_status IN ('NO_ALERT', 'ALERT')),
    recommendation_text VARCHAR(1000) NOT NULL,
    UNIQUE (segment_label, alert_status)
);

-- 曝光：用户在某个频道入口的信息流里"看到"一条内容；点击的曝光对应一条 pv_log。
CREATE TABLE IF NOT EXISTS impression (
    id                  BIGSERIAL PRIMARY KEY,
    viewer_employee_id  BIGINT NOT NULL REFERENCES employee(id),
    content_id          BIGINT NOT NULL REFERENCES content(id),
    entry_channel_id    BIGINT NOT NULL REFERENCES channel(id),
    impression_time     TIMESTAMP NOT NULL,
    clicked             BOOLEAN NOT NULL,
    simulation_batch_id VARCHAR(64)
);

-- 关注关系：员工关注某个账号；unfollow_time 为空表示仍在关注。只记录，不计分。
CREATE TABLE IF NOT EXISTS follow_relation (
    id                   BIGSERIAL PRIMARY KEY,
    follower_employee_id BIGINT NOT NULL REFERENCES employee(id),
    account_id           BIGINT NOT NULL REFERENCES account(id),
    follow_time          TIMESTAMP NOT NULL,
    unfollow_time        TIMESTAMP,
    simulation_batch_id  VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS search_log (
    id                  BIGSERIAL PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES employee(id),
    keyword             VARCHAR(100) NOT NULL,
    search_time         TIMESTAMP NOT NULL,
    clicked_content_id  BIGINT REFERENCES content(id),
    simulation_batch_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS content_tag (
    id                  BIGSERIAL PRIMARY KEY,
    content_id          BIGINT NOT NULL REFERENCES content(id),
    tag                 VARCHAR(50) NOT NULL,
    simulation_batch_id VARCHAR(64),
    UNIQUE (content_id, tag)
);

CREATE INDEX IF NOT EXISTS idx_impression_entry_time ON impression(entry_channel_id, impression_time);
CREATE INDEX IF NOT EXISTS idx_impression_content ON impression(content_id);
CREATE INDEX IF NOT EXISTS idx_follow_account ON follow_relation(account_id);
CREATE INDEX IF NOT EXISTS idx_follow_follower ON follow_relation(follower_employee_id);
CREATE INDEX IF NOT EXISTS idx_search_log_time ON search_log(search_time);
CREATE INDEX IF NOT EXISTS idx_content_tag_content ON content_tag(content_id);
CREATE INDEX IF NOT EXISTS idx_content_tag_tag ON content_tag(tag);
