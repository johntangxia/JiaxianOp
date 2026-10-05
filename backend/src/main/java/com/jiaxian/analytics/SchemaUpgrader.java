package com.jiaxian.analytics;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 给已经存在的旧库补列。项目没有迁移工具，OpenGauss 又不支持 {@code ADD COLUMN IF NOT EXISTS}，
 * 所以启动时查 information_schema，缺列才 ALTER；表还不存在（新库）就跳过，由 schema.sql 直接建出带列的表。
 * 它通过 {@link Registration} 排在 Hibernate 的 {@code ddl-auto: validate} 之前，否则旧库会因为缺列而启动失败。
 * 以后要给旧表加列，只需要在 {@link #COLUMNS} 里追加一行。
 */
@Component("schemaUpgrader")
public class SchemaUpgrader implements InitializingBean {

    record ColumnSpec(String table, String column, String type) {
    }

    static final List<ColumnSpec> COLUMNS = List.of(
        new ColumnSpec("employee", "community_join_date", "DATE"));

    private final JdbcTemplate jdbc;

    /**
     * 故意直接用 DataSource 自己建 JdbcTemplate：自动配置的 JdbcTemplate 被 Spring Boot 标记为"依赖数据库初始化"，
     * 而 defer-datasource-initialization 又让初始化依赖 EntityManagerFactory，用它会形成循环依赖。
     */
    public SchemaUpgrader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        for (ColumnSpec spec : COLUMNS) {
            addColumnIfMissing(spec);
        }
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
