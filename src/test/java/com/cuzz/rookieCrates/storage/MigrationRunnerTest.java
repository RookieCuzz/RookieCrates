package com.cuzz.rookieCrates.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void splitterPreservesQuotedSemicolonsAndSkipsComments() {
        String script = """
                -- comment;
                CREATE TABLE sample(value TEXT);
                INSERT INTO sample VALUES ('it''s;fine');
                /* ignored ; */ INSERT INTO sample VALUES ("double;quoted");
                """;
        List<String> statements = MigrationRunner.splitStatements(script);
        assertEquals(3, statements.size());
        assertEquals("INSERT INTO sample VALUES ('it''s;fine')", statements.get(1));
    }

    @Test
    void upgradesVersionOneRecoveryTableAndRenamesDefaultCrateModel() throws Exception {
        Path file = tempDirectory.resolve("version-one.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE schema_version (
                        version INTEGER PRIMARY KEY,
                        resource TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "INSERT INTO schema_version VALUES (1, 'db/V001__initial.sql', 1)");
            statement.executeUpdate("""
                    CREATE TABLE scene_profiles (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        crate_model TEXT NOT NULL,
                        loot_model TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO scene_profiles(id, name, crate_model, loot_model)
                    VALUES ('default', 'Default', 'xi_crate1', 'loot_white')
                    """);
            statement.executeUpdate("""
                    CREATE TABLE active_scene_recovery (
                        player_uuid TEXT PRIMARY KEY,
                        created_at INTEGER NOT NULL
                    )
                    """);
        }

        try (SQLiteDatabase database = new SQLiteDatabase(file)) {
            database.start();
            assertEquals(3, database.submit(RookieCratesDao::schemaVersion).join());
        }

        boolean statusColumn = false;
        boolean appliedAtColumn = false;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(active_scene_recovery)")) {
            while (columns.next()) {
                statusColumn |= "recovery_status".equals(columns.getString("name"));
                appliedAtColumn |= "applied_at".equals(columns.getString("name"));
            }
        }
        assertTrue(statusColumn);
        assertTrue(appliedAtColumn);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet profile = statement.executeQuery(
                     "SELECT crate_model FROM scene_profiles WHERE id = 'default'")) {
            assertTrue(profile.next());
            assertEquals("default_crate", profile.getString("crate_model"));
        }
    }
}
