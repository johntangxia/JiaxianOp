package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 批量写入的行必须和逐条的 {@code BehaviorEventGenerator.generateView} 逐行一致。 */
@SpringBootTest
@Transactional
class ViewBatchWriterIT {

    private static final String BATCH = "view-writer-it";
    private static final LocalDateTime T1 = LocalDateTime.of(2019, 6, 10, 10, 15);
    private static final LocalDateTime T2 = LocalDateTime.of(2019, 6, 10, 11, 40);

    @Autowired private ViewBatchWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentAuthorRepository contentAuthorRepository;
    @Autowired private PvLogRepository pvLogRepository;
    @Autowired private EngagementLogRepository engagementLogRepository;
    @Autowired private ActiveActionLogRepository activeActionLogRepository;
    @Autowired private PointsWeightConfigRepository pointsWeightConfigRepository;
    @Autowired private PointsLedgerRepository pointsLedgerRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private ImpressionRepository impressionRepository;
    @Autowired private FollowRelationRepository followRelationRepository;
    @Autowired private SearchLogRepository searchLogRepository;
    @Autowired private ContentTagRepository contentTagRepository;

    private BehaviorEventGenerator generator;
    private Employee viewer;
    private Employee authorOne;
    private Employee authorTwo;
    private Channel domain;
    private Channel entry;
    private Content batchContent;
    private Content singleContent;

    private Employee employee(String name) {
        return employeeRepository.save(Employee.builder().name(name).dept("浏览写入测试部")
            .communityJoinDate(LocalDate.of(2019, 1, 1)).simulationBatchId(BATCH).build());
    }

    private Content content() {
        Account account = accountRepository.save(Account.builder().name("浏览写入测试账号").type(AccountType.TEAM)
            .simulationBatchId(BATCH).build());
        Content content = contentRepository.save(Content.builder().type(ContentType.ARTICLE).channelId(domain.getId())
            .accountId(account.getId()).publishTime(LocalDateTime.of(2019, 6, 1, 9, 0)).popularityWeight(0.5)
            .simulationBatchId(BATCH).build());
        for (Employee author : List.of(authorOne, authorTwo)) {
            contentAuthorRepository.save(ContentAuthor.builder().contentId(content.getId()).employeeId(author.getId())
                .simulationBatchId(BATCH).build());
        }
        return content;
    }

    @BeforeEach
    void fixtures() {
        viewer = employee("浏览写入测试员工1");
        authorOne = employee("浏览写入测试员工2");
        authorTwo = employee("浏览写入测试员工3");
        domain = channelRepository.save(Channel.builder().name("浏览写入测试领域").category("领域频道").build());
        entry = channelRepository.save(Channel.builder().name("浏览写入测试综合").category("综合频道").build());
        batchContent = content();
        singleContent = content();
        generator = new BehaviorEventGenerator(pvLogRepository, engagementLogRepository, activeActionLogRepository,
            contentAuthorRepository, pointsWeightConfigRepository, pointsLedgerRepository, commentRepository,
            impressionRepository, followRelationRepository, searchLogRepository, contentTagRepository, new Random(1));
    }

    private List<String> rows(String sql, long contentId) {
        return jdbc.query(sql, (rs, i) -> {
            StringBuilder row = new StringBuilder();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                row.append(rs.getObject(c)).append('|');
            }
            return row.toString();
        }, contentId);
    }

    private void assertSameRows(long a, long b) {
        for (String sql : new String[] {
            "SELECT viewer_employee_id, view_time, entry_channel_id, source_type, dwell_seconds, completed FROM pv_log"
                + " WHERE content_id = ? ORDER BY view_time",
            "SELECT viewer_employee_id, entry_channel_id, impression_time, clicked FROM impression WHERE content_id = ?"
                + " ORDER BY impression_time",
            "SELECT employee_id, action_type, occur_time FROM active_action_log WHERE ref_id = ? AND action_type = 'VIEW'"
                + " ORDER BY occur_time",
            "SELECT employee_id, action_type, category, points, occur_time FROM points_ledger WHERE ref_id = ?"
                + " AND action_type IN ('VIEW', 'CONTENT_VIEWED') ORDER BY occur_time, employee_id, action_type"}) {
            assertThat(rows(sql, a)).as(sql).isNotEmpty().isEqualTo(rows(sql, b));
        }
    }

    @Test
    void batchRowsAreIdenticalToTheOnesWrittenViewByView() {
        ViewDetail entryDetail = new ViewDetail(ViewSource.ENTRY, entry.getId(), 90, true);
        ViewDetail shareDetail = new ViewDetail(ViewSource.SHARE, null, 33, false);

        writer.writeViews(List.of(
                new ViewBatchWriter.ViewRow(batchContent.getId(), viewer.getId(), T1, entryDetail),
                new ViewBatchWriter.ViewRow(batchContent.getId(), viewer.getId(), T2, shareDetail)),
            Map.of(batchContent.getId(), List.of(authorOne.getId(), authorTwo.getId())),
            generator.viewWeight(), generator.contentViewedWeight(), BATCH);
        generator.generateView(singleContent.getId(), viewer.getId(), T1, BATCH, entryDetail);
        generator.generateView(singleContent.getId(), viewer.getId(), T2, BATCH, shareDetail);

        assertSameRows(batchContent.getId(), singleContent.getId());
        // 入口来源有一条已点击的曝光，分享没有；作者平分被动积分（两个作者各一条）
        assertThat(rows("SELECT clicked FROM impression WHERE content_id = ?", batchContent.getId())).hasSize(1);
        assertThat(rows("SELECT points FROM points_ledger WHERE ref_id = ? AND action_type = 'CONTENT_VIEWED'",
            batchContent.getId())).hasSize(4);
    }

    @Test
    void viewsWithoutDetailAreStoredWithNullColumnsLikeTheOldPath() {
        writer.writeViews(List.of(new ViewBatchWriter.ViewRow(batchContent.getId(), viewer.getId(), T1, ViewDetail.NONE)),
            Map.of(batchContent.getId(), List.of(authorOne.getId())), 1, 2, BATCH);
        generator.generateView(singleContent.getId(), viewer.getId(), T1, BATCH);

        assertThat(rows("SELECT viewer_employee_id, view_time, entry_channel_id, source_type, dwell_seconds, completed"
            + " FROM pv_log WHERE content_id = ?", batchContent.getId()))
            .containsExactly(viewer.getId() + "|2019-06-10 10:15:00.0|null|null|null|null|");
        assertThat(rows("SELECT COUNT(*) FROM impression WHERE content_id = ?", batchContent.getId())).containsExactly("0|");
    }

    @Test
    void impressionsAndSearchesAreWrittenWithTheirFlags() {
        writer.writeImpressions(List.of(
            new ViewBatchWriter.ImpressionRow(viewer.getId(), batchContent.getId(), entry.getId(), T1, false),
            new ViewBatchWriter.ImpressionRow(authorOne.getId(), batchContent.getId(), entry.getId(), T2, true)), BATCH);
        writer.writeSearches(List.of(
            new ViewBatchWriter.SearchRow(viewer.getId(), "浏览写入关键词", T1, batchContent.getId()),
            new ViewBatchWriter.SearchRow(authorOne.getId(), "浏览写入无点击", T2, null)), BATCH);

        assertThat(rows("SELECT viewer_employee_id, clicked FROM impression WHERE content_id = ? ORDER BY impression_time",
            batchContent.getId())).containsExactly(viewer.getId() + "|false|", authorOne.getId() + "|true|");
        assertThat(jdbc.queryForList("SELECT keyword FROM search_log WHERE keyword LIKE '浏览写入%' AND clicked_content_id IS NULL",
            String.class)).containsExactly("浏览写入无点击");
        assertThat(jdbc.queryForList("SELECT keyword FROM search_log WHERE clicked_content_id = ?", String.class,
            batchContent.getId())).containsExactly("浏览写入关键词");
    }

    @Test
    void writingMoreRowsThanOneChunkWorks() {
        List<ViewBatchWriter.ViewRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < ViewBatchWriter.CHUNK_SIZE + 5; i++) {
            rows.add(new ViewBatchWriter.ViewRow(batchContent.getId(), viewer.getId(), T1.plusSeconds(i), ViewDetail.NONE));
        }

        writer.writeViews(rows, Map.of(), 1, 2, BATCH);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pv_log WHERE content_id = ?", Long.class, batchContent.getId()))
            .isEqualTo(ViewBatchWriter.CHUNK_SIZE + 5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_action_log WHERE ref_id = ? AND action_type = 'VIEW'",
            Long.class, batchContent.getId())).isEqualTo(ViewBatchWriter.CHUNK_SIZE + 5L);
    }
}
