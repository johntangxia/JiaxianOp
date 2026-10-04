package com.jiaxian.analytics.pv;

import com.jiaxian.analytics.domain.Account;
import com.jiaxian.analytics.domain.Channel;
import com.jiaxian.analytics.domain.Content;
import com.jiaxian.analytics.domain.ContentType;
import com.jiaxian.analytics.domain.Employee;
import com.jiaxian.analytics.repository.AccountRepository;
import com.jiaxian.analytics.repository.ChannelRepository;
import com.jiaxian.analytics.repository.ContentAuthorRepository;
import com.jiaxian.analytics.repository.ContentRepository;
import com.jiaxian.analytics.repository.EmployeeRepository;
import com.jiaxian.analytics.repository.PvLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PvSummaryServiceIT {

    @Autowired private PvSummaryService summaryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;

    private PvTestFixtures fixtures;
    private Employee viewer;
    private Content content;

    @BeforeEach
    void setUp() {
        fixtures = new PvTestFixtures(channelRepository, accountRepository, employeeRepository,
            contentRepository, contentAuthorRepository, pvLogRepository);
        Channel channel = fixtures.channel("PV测试频道-汇总");
        Account account = fixtures.account("PV测试账号-汇总");
        Employee author = fixtures.employee("PV测试作者-汇总");
        viewer = fixtures.employee("PV测试读者-汇总");
        content = fixtures.content(ContentType.ARTICLE, channel, account, author);
        // 2019-05：01 日 2 次（含 00:00:00 和 23:59:59 两个边界），02 日 1 次，04 日 1 次
        fixtures.views(content, viewer,
            LocalDateTime.of(2019, 5, 1, 0, 0, 0), LocalDateTime.of(2019, 5, 1, 23, 59, 59),
            LocalDateTime.of(2019, 5, 2, 10, 0), LocalDateTime.of(2019, 5, 4, 8, 30));
    }

    private Map<LocalDate, Long> summary() {
        Map<LocalDate, Long> rows = new TreeMap<>();
        jdbc.query("SELECT summary_date, pv_count FROM pv_summary_daily WHERE content_id = ?",
            rs -> {
                rows.put(rs.getTimestamp(1).toLocalDateTime().toLocalDate(), rs.getLong(2));
            }, content.getId());
        return rows;
    }

    private void deleteViewsOn(LocalDate day) {
        jdbc.update("DELETE FROM pv_log WHERE content_id = ? AND view_time >= ? AND view_time < ?",
            content.getId(), Timestamp.valueOf(day.atStartOfDay()), Timestamp.valueOf(day.plusDays(1).atStartOfDay()));
    }

    @Test
    void rebuildWritesOneRowPerContentAndDay() {
        int written = summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(written).isEqualTo(3);
        assertThat(summary()).containsExactly(
            Map.entry(LocalDate.of(2019, 5, 1), 2L),
            Map.entry(LocalDate.of(2019, 5, 2), 1L),
            Map.entry(LocalDate.of(2019, 5, 4), 1L));
    }

    @Test
    void rebuildIsIdempotent() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        Map<LocalDate, Long> first = summary();

        int writtenAgain = summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(writtenAgain).isEqualTo(3);
        assertThat(summary()).isEqualTo(first);
    }

    @Test
    void rebuildOnlyTouchesTheRequestedRangeAndBothEndDaysAreIncluded() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        // 5 月 2 日又多了一次浏览；5 月 4 日的浏览被删掉
        fixtures.views(content, viewer, LocalDateTime.of(2019, 5, 2, 18, 0));
        deleteViewsOn(LocalDate.of(2019, 5, 4));

        int written = summaryService.rebuild(LocalDate.of(2019, 5, 2), LocalDate.of(2019, 5, 2));

        assertThat(written).isEqualTo(1);
        assertThat(summary()).containsExactly(
            Map.entry(LocalDate.of(2019, 5, 1), 2L),   // 范围外，保持不变
            Map.entry(LocalDate.of(2019, 5, 2), 2L),   // 重算了
            Map.entry(LocalDate.of(2019, 5, 4), 1L));  // 范围外，旧数据保持不变
    }

    @Test
    void rebuildRemovesStaleRowsInTheRange() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        deleteViewsOn(LocalDate.of(2019, 5, 4));

        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));

        assertThat(summary()).containsOnlyKeys(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 2));
    }

    @Test
    void nullBoundsRebuildEverythingAndTotalsMatchPvLog() {
        summaryService.rebuild(null, null);

        Long summaryTotal = jdbc.queryForObject("SELECT COALESCE(SUM(pv_count), 0) FROM pv_summary_daily", Long.class);
        Long pvLogTotal = jdbc.queryForObject("SELECT COUNT(*) FROM pv_log", Long.class);
        assertThat(summaryTotal).isEqualTo(pvLogTotal);
        assertThat(summary()).hasSize(3);
    }

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> summaryService.rebuild(LocalDate.of(2019, 5, 31), LocalDate.of(2019, 5, 1)))
            .isInstanceOf(InvalidPvQueryException.class);
    }

    @Test
    void deletingContentCascadesToSummaryRows() {
        summaryService.rebuild(LocalDate.of(2019, 5, 1), LocalDate.of(2019, 5, 31));
        assertThat(summary()).isNotEmpty();

        jdbc.update("DELETE FROM pv_log WHERE content_id = ?", content.getId());
        jdbc.update("DELETE FROM content_author WHERE content_id = ?", content.getId());
        jdbc.update("DELETE FROM content WHERE id = ?", content.getId());

        assertThat(summary()).isEmpty();
    }
}
