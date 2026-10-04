package com.jiaxian.analytics.kol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PlaybookServiceIT {

    @Autowired private PlaybookService service;

    @Test
    void seedDataIsPresent() {
        List<PlaybookEntry> entries = service.list();

        assertThat(entries).extracting(e -> e.segmentLabel() + "/" + e.alertStatus()).contains(
            "STAR_KOL/NO_ALERT", "STEADY/NO_ALERT", "RISING/NO_ALERT",
            "CHURN_RISK/ALERT", "DORMANT/NO_ALERT", "OBSERVE/NO_ALERT");
        assertThat(service.lookup().find(Segment.STAR_KOL, false)).contains("专家专栏");
    }

    @Test
    void createUpdateAndDelete() {
        PlaybookEntry created = service.create(new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "观察中且有预警：先人工看一眼"));

        assertThat(created.id()).isPositive();
        assertThat(created.segmentLabel()).isEqualTo(Segment.OBSERVE);
        assertThat(created.alertStatus()).isEqualTo(PlaybookAlertStatus.ALERT);

        PlaybookEntry updated = service.update(created.id(),
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "改过的文案"));
        assertThat(updated.recommendationText()).isEqualTo("改过的文案");
        assertThat(service.list()).extracting(PlaybookEntry::id).contains(created.id());

        service.delete(created.id());
        assertThat(service.list()).extracting(PlaybookEntry::id).doesNotContain(created.id());
    }

    @Test
    void duplicateSegmentAndAlertStatusIsAConflict() {
        assertThatThrownBy(() -> service.create(
            new PlaybookRequest(Segment.STAR_KOL, PlaybookAlertStatus.NO_ALERT, "重复")))
            .isInstanceOf(PlaybookConflictException.class);

        PlaybookEntry other = service.create(
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "另一条"));
        assertThatThrownBy(() -> service.update(other.id(),
            new PlaybookRequest(Segment.STAR_KOL, PlaybookAlertStatus.NO_ALERT, "改成已存在的键")))
            .isInstanceOf(PlaybookConflictException.class);
        // 更新成自己原来的键不算冲突
        assertThat(service.update(other.id(),
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "只改文案")).recommendationText())
            .isEqualTo("只改文案");
    }

    @Test
    void missingEntriesAreNotFound() {
        assertThatThrownBy(() -> service.update(-1L,
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "x")))
            .isInstanceOf(PlaybookNotFoundException.class);
        assertThatThrownBy(() -> service.delete(-1L)).isInstanceOf(PlaybookNotFoundException.class);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> service.create(new PlaybookRequest(null, PlaybookAlertStatus.ALERT, "x")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("segmentLabel");
        assertThatThrownBy(() -> service.create(new PlaybookRequest(Segment.OBSERVE, null, "x")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("alertStatus");
        assertThatThrownBy(() -> service.create(new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "  ")))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("recommendationText");
        assertThatThrownBy(() -> service.create(
            new PlaybookRequest(Segment.OBSERVE, PlaybookAlertStatus.ALERT, "字".repeat(1001))))
            .isInstanceOf(InvalidKolQueryException.class).hasMessageContaining("1000");
    }

    @Test
    void lookupPrefersTheExactMatchThenFallsBackToNoAlertThenNull() {
        PlaybookLookup lookup = service.lookup();

        // CHURN_RISK 只有 ALERT 的种子：精确命中
        assertThat(lookup.find(Segment.CHURN_RISK, true)).contains("私信关怀");
        // DORMANT 有预警但没有 (DORMANT, ALERT)：回退到 (DORMANT, NO_ALERT)
        assertThat(lookup.find(Segment.DORMANT, true)).contains("低成本触达");
        // CHURN_RISK 没有 (CHURN_RISK, NO_ALERT) 也没有回退可用：为 null
        assertThat(lookup.find(Segment.CHURN_RISK, false)).isNull();
    }
}
