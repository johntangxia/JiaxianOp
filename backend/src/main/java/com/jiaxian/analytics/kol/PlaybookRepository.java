package com.jiaxian.analytics.kol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlaybookRepository {

    private static final String SELECT =
        "SELECT id, segment_label, alert_status, recommendation_text FROM action_playbook";

    private static final RowMapper<PlaybookEntry> MAPPER = (rs, rowNum) -> new PlaybookEntry(
        rs.getLong(1), Segment.valueOf(rs.getString(2)),
        PlaybookAlertStatus.valueOf(rs.getString(3)), rs.getString(4));

    private final JdbcTemplate jdbc;

    public PlaybookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlaybookEntry> list() {
        return jdbc.query(SELECT + " ORDER BY id", MAPPER);
    }

    public Optional<PlaybookEntry> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<PlaybookEntry> findByKey(Segment segment, PlaybookAlertStatus alertStatus) {
        return jdbc.query(SELECT + " WHERE segment_label = ? AND alert_status = ?", MAPPER,
            segment.name(), alertStatus.name()).stream().findFirst();
    }

    public void insert(Segment segment, PlaybookAlertStatus alertStatus, String text) {
        jdbc.update("INSERT INTO action_playbook (segment_label, alert_status, recommendation_text) VALUES (?, ?, ?)",
            segment.name(), alertStatus.name(), text);
    }

    public int update(long id, Segment segment, PlaybookAlertStatus alertStatus, String text) {
        return jdbc.update(
            "UPDATE action_playbook SET segment_label = ?, alert_status = ?, recommendation_text = ? WHERE id = ?",
            segment.name(), alertStatus.name(), text, id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM action_playbook WHERE id = ?", id);
    }
}
