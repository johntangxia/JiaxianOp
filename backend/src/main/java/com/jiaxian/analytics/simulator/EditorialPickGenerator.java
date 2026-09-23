package com.jiaxian.analytics.simulator;

import com.jiaxian.analytics.domain.*;
import com.jiaxian.analytics.repository.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class EditorialPickGenerator {

    private final ContentAuthorRepository contentAuthorRepository;
    private final EditorialPickLogRepository editorialPickLogRepository;
    private final PointsWeightConfigRepository pointsWeightConfigRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final Random random;

    public EditorialPickGenerator(ContentAuthorRepository contentAuthorRepository,
                                   EditorialPickLogRepository editorialPickLogRepository,
                                   PointsWeightConfigRepository pointsWeightConfigRepository,
                                   PointsLedgerRepository pointsLedgerRepository,
                                   Random random) {
        this.contentAuthorRepository = contentAuthorRepository;
        this.editorialPickLogRepository = editorialPickLogRepository;
        this.pointsWeightConfigRepository = pointsWeightConfigRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.random = random;
    }

    /**
     * 从候选内容里按热度权重降序取前 pickCount 篇，模拟"入选思想简报"编辑精选动作。
     */
    public void pickTopContent(List<Content> candidates, int pickCount, LocalDateTime pickTime, String batchId) {
        int weight = pointsWeightConfigRepository
            .findFirstByActionTypeOrderByEffectiveDateDesc("EDITORIAL_PICK")
            .map(PointsWeightConfig::getWeight)
            .orElseThrow(() -> new IllegalStateException("Missing points weight config for EDITORIAL_PICK"));

        candidates.stream()
            .sorted(Comparator.comparingDouble(Content::getPopularityWeight).reversed())
            .limit(pickCount)
            .forEach(content -> {
                editorialPickLogRepository.save(EditorialPickLog.builder()
                    .contentId(content.getId()).pickTime(pickTime).simulationBatchId(batchId).build());

                List<ContentAuthor> authors = contentAuthorRepository.findByContentId(content.getId());
                int perAuthorPoints = authors.isEmpty() ? 0 : weight / authors.size();
                for (ContentAuthor author : authors) {
                    pointsLedgerRepository.save(PointsLedger.builder()
                        .employeeId(author.getEmployeeId()).actionType("EDITORIAL_PICK")
                        .category(PointsCategory.PASSIVE).points(perAuthorPoints)
                        .refId(content.getId()).occurTime(pickTime).simulationBatchId(batchId).build());
                }
            });
    }
}
