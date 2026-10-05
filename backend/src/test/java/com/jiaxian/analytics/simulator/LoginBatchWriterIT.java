package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LoginBatchWriterIT {

    private static final String BATCH = "login-batch-it";

    @Autowired private LoginBatchWriter writer;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void writesOneLoginActionAndOneActiveLedgerEntryPerLoginAcrossChunks() {
        Employee employee = employeeRepository.save(Employee.builder().name("登录批量测试员工").simulationBatchId(BATCH).build());
        List<LoginBatchWriter.Login> logins = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {   // 超过一个批次（1000）
            logins.add(new LoginBatchWriter.Login(employee.getId(), LocalDateTime.of(2019, 6, 1, 8, 0).plusMinutes(i)));
        }

        writer.write(logins, 20, BATCH);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE simulation_batch_id = ?"
            + " AND action_type = 'LOGIN' AND ref_id IS NULL AND employee_id = ?", Long.class, BATCH, employee.getId())).isEqualTo(2_500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_ledger WHERE simulation_batch_id = ?"
            + " AND action_type = 'LOGIN' AND category = 'ACTIVE' AND points = 20 AND ref_id IS NULL", Long.class, BATCH))
            .isEqualTo(2_500);
        assertThat(jdbc.queryForObject("SELECT MIN(occur_time) FROM active_action_log WHERE simulation_batch_id = ?",
            java.sql.Timestamp.class, BATCH).toLocalDateTime()).isEqualTo(LocalDateTime.of(2019, 6, 1, 8, 0));
    }

    @Test
    void anEmptyListWritesNothing() {
        writer.write(List.of(), 20, BATCH);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE simulation_batch_id = ?", Long.class, BATCH))
            .isZero();
    }
}
