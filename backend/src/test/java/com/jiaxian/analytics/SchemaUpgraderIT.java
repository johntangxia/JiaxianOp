package com.jiaxian.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 会临时删列再补回来，所以不能 @Transactional（DDL 在事务里回滚行为不统一），结束时列一定在。 */
@SpringBootTest
class SchemaUpgraderIT {

    @Autowired private SchemaUpgrader upgrader;
    @Autowired private JdbcTemplate jdbc;

    private int columnCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
            + " WHERE table_schema = current_schema() AND table_name = 'employee'"
            + " AND column_name = 'community_join_date'", Integer.class);
    }

    @Test
    void addsTheMissingColumnAndIsIdempotent() {
        jdbc.execute("ALTER TABLE employee DROP COLUMN community_join_date");
        assertThat(columnCount()).isZero();

        upgrader.afterPropertiesSet();
        assertThat(columnCount()).isEqualTo(1);

        assertThatCode(upgrader::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(columnCount()).isEqualTo(1);
    }
}
