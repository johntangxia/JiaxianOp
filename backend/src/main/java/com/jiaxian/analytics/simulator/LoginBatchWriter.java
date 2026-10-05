package com.jiaxian.analytics.simulator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史回填里登录事件占了绝大多数行（每个登录一条主动行为明细 + 一条积分流水），逐条 JPA 保存太慢，
 * 这里用 JDBC 批量写入。写入的内容和 {@link BehaviorEventGenerator#generateLogin} 完全一样：
 * 一条 LOGIN 主动行为 + 一条 ACTIVE 类的 LOGIN 积分流水。在回填的事务里调用，和其他写入同生共死。
 */
@Component
public class LoginBatchWriter {

    static final int CHUNK_SIZE = 1000;

    public record Login(long employeeId, LocalDateTime time) {
    }

    private final JdbcTemplate jdbc;

    public LoginBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(List<Login> logins, int loginWeight, String batchId) {
        for (int from = 0; from < logins.size(); from += CHUNK_SIZE) {
            List<Login> chunk = logins.subList(from, Math.min(logins.size(), from + CHUNK_SIZE));
            jdbc.batchUpdate("INSERT INTO active_action_log (employee_id, action_type, ref_id, occur_time, simulation_batch_id)"
                + " VALUES (?, 'LOGIN', NULL, ?, ?)", chunk, chunk.size(), (ps, login) -> {
                    ps.setLong(1, login.employeeId());
                    ps.setTimestamp(2, Timestamp.valueOf(login.time()));
                    ps.setString(3, batchId);
                });
            jdbc.batchUpdate("INSERT INTO points_ledger (employee_id, action_type, category, points, ref_id, occur_time,"
                + " simulation_batch_id) VALUES (?, 'LOGIN', 'ACTIVE', ?, NULL, ?, ?)", chunk, chunk.size(), (ps, login) -> {
                    ps.setLong(1, login.employeeId());
                    ps.setInt(2, loginWeight);
                    ps.setTimestamp(3, Timestamp.valueOf(login.time()));
                    ps.setString(4, batchId);
                });
        }
    }
}
