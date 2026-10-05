package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.ViewSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史回填里浏览占了绝大多数行：每次浏览背后有 pv_log、VIEW 主动行为、浏览者的主动积分、作者的被动积分，
 * 入口来源还有一条"已点击"的曝光——逐条 JPA 保存太慢，这里用 JDBC 批量写入。
 * 写入的内容和 {@link BehaviorEventGenerator#generateView(Long, Long, LocalDateTime, String, ViewDetail)} 逐行一致。
 * 在回填的事务里调用，和其他写入同生共死。
 */
@Component
public class ViewBatchWriter {

    static final int CHUNK_SIZE = 2000;

    public record ViewRow(long contentId, long viewerId, LocalDateTime time, ViewDetail detail) {
    }

    public record ImpressionRow(long viewerId, long contentId, long entryChannelId, LocalDateTime time, boolean clicked) {
    }

    public record SearchRow(long employeeId, String keyword, LocalDateTime time, Long clickedContentId) {
    }

    private final JdbcTemplate jdbc;

    public ViewBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param authorsByContent  内容 id → 作者员工 id；每个作者平分 {@code contentViewedWeight}（整除，和逐条写入一致）
     * @param viewWeight        浏览者"浏览"的主动积分权重
     * @param contentViewedWeight 作者"内容被浏览"的被动积分总权重
     */
    public void writeViews(List<ViewRow> views, Map<Long, List<Long>> authorsByContent, int viewWeight,
                           int contentViewedWeight, String batchId) {
        for (int from = 0; from < views.size(); from += CHUNK_SIZE) {
            List<ViewRow> chunk = views.subList(from, Math.min(views.size(), from + CHUNK_SIZE));
            jdbc.batchUpdate("INSERT INTO pv_log (content_id, viewer_employee_id, view_time, entry_channel_id, source_type,"
                + " dwell_seconds, completed, simulation_batch_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", chunk, chunk.size(),
                (ps, view) -> {
                    ViewDetail d = view.detail();
                    ps.setLong(1, view.contentId());
                    ps.setLong(2, view.viewerId());
                    ps.setTimestamp(3, Timestamp.valueOf(view.time()));
                    if (d.entryChannelId() == null) {
                        ps.setNull(4, Types.BIGINT);
                    } else {
                        ps.setLong(4, d.entryChannelId());
                    }
                    if (d.source() == null) {
                        ps.setNull(5, Types.VARCHAR);
                    } else {
                        ps.setString(5, d.source().name());
                    }
                    if (d.dwellSeconds() == null) {
                        ps.setNull(6, Types.INTEGER);
                    } else {
                        ps.setInt(6, d.dwellSeconds());
                    }
                    if (d.completed() == null) {
                        ps.setNull(7, Types.BOOLEAN);
                    } else {
                        ps.setBoolean(7, d.completed());
                    }
                    ps.setString(8, batchId);
                });

            List<ImpressionRow> clicked = new ArrayList<>();
            for (ViewRow view : chunk) {
                ViewDetail d = view.detail();
                if (d.source() == ViewSource.ENTRY && d.entryChannelId() != null) {
                    clicked.add(new ImpressionRow(view.viewerId(), view.contentId(), d.entryChannelId(), view.time(), true));
                }
            }
            writeImpressions(clicked, batchId);

            jdbc.batchUpdate("INSERT INTO active_action_log (employee_id, action_type, ref_id, occur_time, simulation_batch_id)"
                + " VALUES (?, 'VIEW', ?, ?, ?)", chunk, chunk.size(), (ps, view) -> {
                    ps.setLong(1, view.viewerId());
                    ps.setLong(2, view.contentId());
                    ps.setTimestamp(3, Timestamp.valueOf(view.time()));
                    ps.setString(4, batchId);
                });
            jdbc.batchUpdate("INSERT INTO points_ledger (employee_id, action_type, category, points, ref_id, occur_time,"
                + " simulation_batch_id) VALUES (?, 'VIEW', 'ACTIVE', ?, ?, ?, ?)", chunk, chunk.size(), (ps, view) -> {
                    ps.setLong(1, view.viewerId());
                    ps.setInt(2, viewWeight);
                    ps.setLong(3, view.contentId());
                    ps.setTimestamp(4, Timestamp.valueOf(view.time()));
                    ps.setString(5, batchId);
                });

            List<Object[]> passive = new ArrayList<>();
            for (ViewRow view : chunk) {
                List<Long> authors = authorsByContent.getOrDefault(view.contentId(), List.of());
                for (long author : authors) {
                    passive.add(new Object[] {author, contentViewedWeight / authors.size(), view.contentId(),
                        Timestamp.valueOf(view.time()), batchId});
                }
            }
            jdbc.batchUpdate("INSERT INTO points_ledger (employee_id, action_type, category, points, ref_id, occur_time,"
                + " simulation_batch_id) VALUES (?, 'CONTENT_VIEWED', 'PASSIVE', ?, ?, ?, ?)", passive);
        }
    }

    public void writeImpressions(List<ImpressionRow> impressions, String batchId) {
        for (int from = 0; from < impressions.size(); from += CHUNK_SIZE) {
            List<ImpressionRow> chunk = impressions.subList(from, Math.min(impressions.size(), from + CHUNK_SIZE));
            jdbc.batchUpdate("INSERT INTO impression (viewer_employee_id, content_id, entry_channel_id, impression_time,"
                + " clicked, simulation_batch_id) VALUES (?, ?, ?, ?, ?, ?)", chunk, chunk.size(), (ps, row) -> {
                    ps.setLong(1, row.viewerId());
                    ps.setLong(2, row.contentId());
                    ps.setLong(3, row.entryChannelId());
                    ps.setTimestamp(4, Timestamp.valueOf(row.time()));
                    ps.setBoolean(5, row.clicked());
                    ps.setString(6, batchId);
                });
        }
    }

    public void writeSearches(List<SearchRow> searches, String batchId) {
        for (int from = 0; from < searches.size(); from += CHUNK_SIZE) {
            List<SearchRow> chunk = searches.subList(from, Math.min(searches.size(), from + CHUNK_SIZE));
            jdbc.batchUpdate("INSERT INTO search_log (employee_id, keyword, search_time, clicked_content_id,"
                + " simulation_batch_id) VALUES (?, ?, ?, ?, ?)", chunk, chunk.size(), (ps, row) -> {
                    ps.setLong(1, row.employeeId());
                    ps.setString(2, row.keyword());
                    ps.setTimestamp(3, Timestamp.valueOf(row.time()));
                    if (row.clickedContentId() == null) {
                        ps.setNull(4, Types.BIGINT);
                    } else {
                        ps.setLong(4, row.clickedContentId());
                    }
                    ps.setString(5, batchId);
                });
        }
    }
}
