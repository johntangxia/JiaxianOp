package com.jiaxian.analytics.kol;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 重算统计日 asOf 的名单、RFM 打分、流失预警和分层。先删后插，同一统计日重复重算结果完全一样。 */
@Service
public class KolRebuildService {

    static final int MAX_WEEKS = 52;

    private final KolMetricsRepository metricsRepository;
    private final KolRfmRepository rfmRepository;
    private final KolProperties properties;
    private final Clock clock;

    public KolRebuildService(KolMetricsRepository metricsRepository, KolRfmRepository rfmRepository,
                             KolProperties properties, Clock clock) {
        this.metricsRepository = metricsRepository;
        this.rfmRepository = rfmRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public KolRebuildResult rebuild(LocalDate asOf) {
        if (asOf.isAfter(LocalDate.now(clock))) {
            throw new InvalidKolQueryException("asOf must not be in the future");
        }
        List<BloggerMetrics> population = metricsRepository.loadPopulation(asOf, properties);
        Map<Long, RfmScores> scores = RfmScorer.score(population);
        Map<Long, Segment> previous = rfmRepository.previousSegments(asOf);

        List<KolRfmRepository.ScoreRow> scoreRows = new ArrayList<>();
        List<KolRfmRepository.AlertRow> alertRows = new ArrayList<>();
        for (BloggerMetrics metrics : population) {
            Optional<ChurnSignal> signal = ChurnDetector.detect(metrics, properties.getRecentDays(),
                properties.getBaselineDays(), properties.getChurnDropThreshold());
            boolean alert = signal.isPresent();
            boolean risingTrend = metrics.recentPoints() > metrics.previousRecentPoints();
            RfmScores rfm = scores.get(metrics.employeeId());
            Segment segment = SegmentClassifier.classify(rfm, alert, previous.get(metrics.employeeId()), risingTrend);

            scoreRows.add(new KolRfmRepository.ScoreRow(metrics, rfm, segment, alert));
            signal.ifPresent(s -> alertRows.add(new KolRfmRepository.AlertRow(metrics.employeeId(), s)));
        }

        rfmRepository.deleteFor(asOf);
        rfmRepository.insertScores(asOf, scoreRows);
        rfmRepository.insertAlerts(asOf, alertRows);
        return new KolRebuildResult(asOf, population.size(), alertRows.size());
    }

    /**
     * 从旧到新依次重算 asOf − 7×(weeks−1) … asOf，让"上一期标签"链条成立，用来补历史。
     * 放在同一个事务里（rebuild 是自调用，事务靠这里的注解提供）。
     */
    @Transactional
    public List<KolRebuildResult> rebuildWeeks(LocalDate asOf, int weeks) {
        if (weeks < 1 || weeks > MAX_WEEKS) {
            throw new InvalidKolQueryException("weeks must be between 1 and " + MAX_WEEKS);
        }
        List<KolRebuildResult> results = new ArrayList<>();
        for (int i = weeks - 1; i >= 0; i--) {
            results.add(rebuild(asOf.minusDays(7L * i)));
        }
        return results;
    }
}
