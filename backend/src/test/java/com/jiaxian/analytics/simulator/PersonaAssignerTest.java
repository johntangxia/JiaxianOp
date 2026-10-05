package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaAssignerTest {

    private static long count(List<BloggerPersona> personas, BloggerPersona wanted) {
        return personas.stream().filter(p -> p == wanted).count();
    }

    @Test
    void aThousandEmployeesGetThreeTwoThreeFivePercentAndTheRestRandom() {
        List<BloggerPersona> personas = PersonaAssigner.assign(1000, 21);

        assertThat(personas).hasSize(1000);
        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(30);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(20);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(30);
        assertThat(count(personas, BloggerPersona.DORMANT)).isEqualTo(50);
        assertThat(count(personas, BloggerPersona.RANDOM)).isEqualTo(870);
    }

    @Test
    void smallTeamsStillGetAtLeastTwoOfEveryPersona() {
        List<BloggerPersona> personas = PersonaAssigner.assign(50, 21);

        assertThat(personas).hasSize(50);
        for (BloggerPersona persona : List.of(BloggerPersona.STAR, BloggerPersona.RISING, BloggerPersona.CHURNING,
            BloggerPersona.DORMANT)) {
            assertThat(count(personas, persona)).as(persona.name()).isGreaterThanOrEqualTo(2);
        }
        assertThat(count(personas, BloggerPersona.RANDOM)).isGreaterThan(30);
    }

    @Test
    void tooFewEmployeesAreSplitWithoutExceedingTheTotal() {
        List<BloggerPersona> personas = PersonaAssigner.assign(5, 3);

        assertThat(personas).hasSize(5);
        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(2);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(2);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(1);
    }

    @Test
    void sameSeedAndSizeGiveTheSameAssignmentAndADifferentSeedShufflesIt() {
        assertThat(PersonaAssigner.assign(50, 21)).isEqualTo(PersonaAssigner.assign(50, 21));
        assertThat(PersonaAssigner.assign(50, 21)).isNotEqualTo(PersonaAssigner.assign(50, 22));
    }

    @Test
    void zeroEmployeesGiveAnEmptyList() {
        assertThat(PersonaAssigner.assign(0, 1)).isEmpty();
    }
}
