package com.jiaxian.analytics;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 在 Hibernate 的 {@code ddl-auto: validate} 之前把库结构准备好：
 * <ol>
 *   <li>给已经存在的旧表补列。项目没有迁移工具，OpenGauss 又不支持 {@code ADD COLUMN IF NOT EXISTS}，
 *       所以查 information_schema，缺列才 ALTER；表还不存在（新库）就跳过。</li>
 *   <li>执行一遍幂等的 {@code schema.sql}（全是 {@code CREATE ... IF NOT EXISTS}）。因为
 *       {@code defer-datasource-initialization=true} 会让 Spring Boot 自己的 schema.sql 在 validate 之后才执行，
 *       新增的、带实体的表如果不在这里先建出来，validate 会报"缺表"；新库也靠这一步才能启动。
 *       之后 Spring Boot 再执行同一份脚本是无副作用的。</li>
 * </ol>
 * 它通过 {@link Registration} 排在 Hibernate 之前。以后要给旧表加列，只需要在 {@link #COLUMNS} 里追加一行，
 * 并同步修改 schema.sql 里的建表语句。
 */
@Component("schemaUpgrader")
public class SchemaUpgrader implements InitializingBean {

    record ColumnSpec(String table, String column, String type) {
    }

    static final List<ColumnSpec> COLUMNS = List.of(
        new ColumnSpec("employee", "community_join_date", "DATE"),
        new ColumnSpec("pv_log", "entry_channel_id", "BIGINT REFERENCES channel(id)"),
        new ColumnSpec("pv_log", "source_type", "VARCHAR(10)"),
        new ColumnSpec("pv_log", "dwell_seconds", "INTEGER"),
        new ColumnSpec("pv_log", "completed", "BOOLEAN"));

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    /**
     * 故意直接用 DataSource 自己建 JdbcTemplate：自动配置的 JdbcTemplate 被 Spring Boot 标记为"依赖数据库初始化"，
     * 而 defer-datasource-initialization 又让初始化依赖 EntityManagerFactory，用它会形成循环依赖。
     */
    public SchemaUpgrader(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        for (ColumnSpec spec : COLUMNS) {
            addColumnIfMissing(spec);
        }
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.execute(dataSource);
    }

    private void addColumnIfMissing(ColumnSpec spec) {
        Integer tables = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
            Integer.class, spec.table());
        if (tables == null || tables == 0) {
            return;
        }
        Integer columns = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?",
            Integer.class, spec.table(), spec.column());
        if (columns == null || columns == 0) {
            jdbc.execute("ALTER TABLE " + spec.table() + " ADD COLUMN " + spec.column() + " " + spec.type());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        static EntityManagerFactoryDependsOnPostProcessor schemaUpgraderBeforeEntityManagerFactory() {
            return new EntityManagerFactoryDependsOnPostProcessor("schemaUpgrader");
        }
    }
}
