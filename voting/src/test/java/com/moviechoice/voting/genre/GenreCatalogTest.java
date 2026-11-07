package com.moviechoice.voting.genre;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GenreCatalogTest {

    @Test
    void mapsTmdbGenreIdsToRussianNames() {
        assertThat(GenreCatalog.toRussian(List.of(878, 53))).isEqualTo("Фантастика, Триллер");
        assertThat(GenreCatalog.toRussian(List.of(35))).isEqualTo("Комедия");
    }

    @Test
    void skipsUnknownIdsAndDuplicates() {
        assertThat(GenreCatalog.toRussian(List.of(18, 99999, 18))).isEqualTo("Драма");
    }

    @Test
    void returnsEmptyStringForMissingGenres() {
        assertThat(GenreCatalog.toRussian(null)).isEmpty();
        assertThat(GenreCatalog.toRussian(List.of())).isEmpty();
    }
}
