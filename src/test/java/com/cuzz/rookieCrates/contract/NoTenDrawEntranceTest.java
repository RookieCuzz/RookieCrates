package com.cuzz.rookieCrates.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoTenDrawEntranceTest {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("java", "yml", "yaml", "sql", "json", "properties");
    private static final List<Pattern> TEN_DRAW_MARKERS = List.of(
            Pattern.compile("十\\s*连"),
            Pattern.compile("10\\s*连"),
            Pattern.compile("(?i)10\\s*(?:draws?|rolls?|pulls?|fold)\\b"),
            Pattern.compile("(?i)\\bTEN_?(?:DRAW|ROLL|PULL|FOLD)\\b"),
            Pattern.compile("(?i)[\"']ten[\"']"),
            Pattern.compile("(?i)DrawType\\s*\\.\\s*TEN"),
            Pattern.compile("(?i)draw[_ -]?count\\s*[:=]\\s*10")
    );

    @Test
    void productionSourcesAndResourcesContainNoTenDrawEntrance() throws IOException {
        Path project = Path.of(System.getProperty("user.dir"));
        List<Path> roots = List.of(project.resolve("src/main/java"), project.resolve("src/main/resources"));
        List<String> violations = new ArrayList<>();

        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).filter(NoTenDrawEntranceTest::isText).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    for (Pattern marker : TEN_DRAW_MARKERS) {
                        if (marker.matcher(content).find()) {
                            violations.add(project.relativize(file) + " matched " + marker);
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> "Ten-draw production entry found:\n" + String.join("\n", violations));
    }

    @Test
    void sqliteMigrationWhitelistsOnlyOneAndSevenDraws() throws IOException {
        Path migration = Path.of(System.getProperty("user.dir"), "src/main/resources/db/V001__initial.sql");
        String sql = Files.readString(migration, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertEquals(1, occurrences(sql, "draw_count integer not null check (draw_count in (1, 7))"));
    }

    private static boolean isText(Path path) {
        String name = path.getFileName().toString();
        int separator = name.lastIndexOf('.');
        return separator >= 0 && TEXT_EXTENSIONS.contains(name.substring(separator + 1).toLowerCase());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
