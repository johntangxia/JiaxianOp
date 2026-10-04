package com.jiaxian.analytics.simulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaAssignerTest {

    private static long count(List<BloggerPersona> personas, BloggerPersona wanted) {
        return personas.stream().filter(p -> p == wanted).count();
    }

    @Test
    void fiftyEmployeesSplitIntoTenTenTenTwentyAndTheRest() {
        List<BloggerPersona> personas = PersonaAssigner.assign(50, 21);

        assertThat(personas).hasSize(50);
        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(5);
        assertThat(count(personas, BloggerPersona.DORMANT)).isEqualTo(10);
        assertThat(count(personas, BloggerPersona.RANDOM)).isEqualTo(25);
    }

    @Test
    void tenEmployeesGetOneOneOneTwoAndFiveRandom() {
        List<BloggerPersona> personas = PersonaAssigner.assign(10, 3);

        assertThat(count(personas, BloggerPersona.STAR)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.RISING)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.CHURNING)).isEqualTo(1);
        assertThat(count(personas, BloggerPersona.DORMANT)).isEqualTo(2);
        assertThat(count(personas, BloggerPersona.RANDOM)).isEqualTo(5);
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
