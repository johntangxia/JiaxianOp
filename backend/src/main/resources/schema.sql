CREATE TABLE IF NOT EXISTS employee (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    dept                VARCHAR(100),
    join_date           DATE,
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
