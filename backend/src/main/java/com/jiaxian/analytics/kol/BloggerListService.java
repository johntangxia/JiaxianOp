package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 名单查询：校验参数、默认取最近一次重算、补齐各分层人数、给每人匹配建议文案。 */
@Service
public class BloggerListService {

    static final int MAX_LIMIT = 1000;

    private final BloggerListRepository listRepository;
    private final KolRfmRepository rfmRepository;
    private final PlaybookService playbookService;

    public BloggerListService(BloggerListRepository listRepository, KolRfmRepository rfmRepository,
                              PlaybookService playbookService) {
        this.listRepository = listRepository;
        this.rfmRepository = rfmRepository;
        this.playbookService = playbookService;
    }

    /** 已有重算记录的统计日，新的在前，给名单页的统计日选择器用。 */
    public List<LocalDate> runs() {
        return rfmRepository.runDates();
    }

    /** asOf 为 null 表示最近一次重算；segment / alert 为 null 表示不筛选。 */
    public BloggerList list(LocalDate asOf, Segment segment, Boolean alert, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidKolQueryException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new InvalidKolQueryException("offset must not be negative");
        }
        LocalDate effective = asOf != null ? asOf : rfmRepository.latestAsOf().orElse(null);
        if (effective == null) {
            return new BloggerList(null, 0, zeroCounts(Map.of()), List.of());
        }

        long total = listRepository.count(effective, segment, alert);
        Map<Segment, Long> counts = zeroCounts(listRepository.segmentCounts(effective));
        PlaybookLookup lookup = playbookService.lookup();
        List<BloggerItem> items = listRepository.find(effective, segment, alert, limit, offset).stream()
            .map(row -> new BloggerItem(row.employeeId(), row.name(), row.rank(), row.rollingPoints12m(),
                row.recencyDays(), row.frequency(), row.monetary(), row.rScore(), row.fScore(), row.mScore(),
                row.segment(), row.segment().displayName(), row.churnAlert(),
                lookup.find(row.segment(), row.churnAlert())))
            .toList();
        return new BloggerList(effective, total, counts, items);
    }

    private static Map<Segment, Long> zeroCounts(Map<Segment, Long> counts) {
        Map<Segment, Long> filled = new EnumMap<>(Segment.class);
        for (Segment segment : Segment.values()) {
            filled.put(segment, counts.getOrDefault(segment, 0L));
        }
        return filled;
    }
}
