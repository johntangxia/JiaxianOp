package com.jiaxian.analytics.pointsconfig;

import com.jiaxian.analytics.repository.PointsWeightConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PointsConfigServiceIT {

    @Autowired private PointsConfigService service;
    @Autowired private PointsWeightConfigRepository engineRepository;

    private PointsConfigItem item(String actionType) {
        return service.list().stream().filter(i -> i.actionType().equals(actionType)).findFirst().orElseThrow();
    }

    private static boolean hasVersionOn(PointsConfigItem item, LocalDate date) {
        return item.versions().stream().anyMatch(v -> v.effectiveDate().equals(date));
    }

    @Test
    void listsAllFifteenActionTypesWithActiveOnesFirst() {
        List<PointsConfigItem> items = service.list();

        assertThat(items).hasSize(15);
        assertThat(items.subList(0, 7)).allSatisfy(i -> assertThat(i.category()).isEqualTo("ACTIVE"));
        assertThat(items.subList(7, 15)).allSatisfy(i -> assertThat(i.category()).isEqualTo("PASSIVE"));
        assertThat(items).allSatisfy(i -> {
            assertThat(i.versions()).isNotEmpty();
            assertThat(i.current()).isEqualTo(i.versions().get(0));
        });
    }

    @Test
    void seedBaselineVersionIsPresent() {
        PointsConfigItem post = item("POST");

        assertThat(post.category()).isEqualTo("ACTIVE");
        assertThat(post.versions()).anyMatch(v -> v.weight() == 100 && v.effectiveDate().equals(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void updateAddsATodayVersionAndOverwritesItOnTheSameDay() {
        LocalDate today = LocalDate.now();
        PointsConfigItem before = item("LIKE");
        int expectedVersions = before.versions().size() + (hasVersionOn(before, today) ? 0 : 1);

        PointsConfigItem updated = service.update("LIKE", 4);

        assertThat(updated.current().weight()).isEqualTo(4);
        assertThat(updated.current().effectiveDate()).isEqualTo(today);
        assertThat(updated.versions()).hasSize(expectedVersions);

        PointsConfigItem overwritten = service.update("LIKE", 5);

        assertThat(overwritten.current().weight()).isEqualTo(5);
        assertThat(overwritten.versions()).hasSize(expectedVersions);
        // 引擎读到的就是最新版本
        assertThat(engineRepository.findFirstByActionTypeOrderByEffectiveDateDesc("LIKE").orElseThrow().getWeight())
            .isEqualTo(5);
    }

    @Test
    void deletingTheNewestVersionMakesThePreviousOneCurrent() {
        PointsConfigItem updated = service.update("SHARE", 9);
        long createdId = updated.current().id();

        service.deleteVersion(createdId);

        assertThat(item("SHARE").versions()).extracting(WeightVersion::id).doesNotContain(createdId);
        assertThat(item("SHARE").current().id()).isNotEqualTo(createdId);
    }

    @Test
    void theOnlyVersionOfAnActionCannotBeDeleted() {
        PointsConfigItem item = item("COMMENT_LIKED");
        // 先把除最后一个以外的版本都删掉
        for (int i = 0; i < item.versions().size() - 1; i++) {
            service.deleteVersion(item.versions().get(i).id());
        }
        long onlyId = item("COMMENT_LIKED").current().id();

        assertThatThrownBy(() -> service.deleteVersion(onlyId))
            .isInstanceOf(PointsConfigConflictException.class).hasMessageContaining("only");
    }

    @Test
    void unknownActionsAndVersionsAreNotFound() {
        assertThatThrownBy(() -> service.update("NO_SUCH_ACTION", 5)).isInstanceOf(PointsConfigNotFoundException.class);
        assertThatThrownBy(() -> service.deleteVersion(-1L)).isInstanceOf(PointsConfigNotFoundException.class);
    }

    @Test
    void weightMustBeAnIntegerBetweenZeroAndTenThousand() {
        assertThatThrownBy(() -> service.update("LIKE", null))
            .isInstanceOf(InvalidPointsConfigException.class).hasMessageContaining("weight");
        assertThatThrownBy(() -> service.update("LIKE", -1)).isInstanceOf(InvalidPointsConfigException.class);
        assertThatThrownBy(() -> service.update("LIKE", 10001)).isInstanceOf(InvalidPointsConfigException.class);
        assertThat(service.update("LIKE", 0).current().weight()).isZero();
        assertThat(service.update("LIKE", 10000).current().weight()).isEqualTo(10000);
    }
}
