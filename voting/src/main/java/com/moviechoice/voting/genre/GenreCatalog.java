package com.moviechoice.voting.genre;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class GenreCatalog {

    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(28, "Боевик");
        NAMES.put(12, "Приключение");
        NAMES.put(16, "Мультфильм");
        NAMES.put(35, "Комедия");
        NAMES.put(80, "Криминал");
        NAMES.put(99, "Документальный");
        NAMES.put(18, "Драма");
        NAMES.put(10751, "Семейный");
        NAMES.put(14, "Фэнтези");
        NAMES.put(36, "История");
        NAMES.put(27, "Ужасы");
        NAMES.put(10402, "Музыка");
        NAMES.put(9648, "Детектив");
        NAMES.put(10749, "Мелодрама");
        NAMES.put(878, "Фантастика");
        NAMES.put(10770, "ТВ-фильм");
        NAMES.put(53, "Триллер");
        NAMES.put(10752, "Военный");
        NAMES.put(37, "Вестерн");
    }

    private GenreCatalog() {
    }

    public static String toRussian(List<Integer> genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return "";
        }
        return genreIds.stream()
                .filter(Objects::nonNull)
                .map(NAMES::get)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    public static String name(Integer genreId) {
        return genreId == null ? null : NAMES.get(genreId);
    }
}
