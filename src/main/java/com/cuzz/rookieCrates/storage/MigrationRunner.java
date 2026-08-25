package com.cuzz.rookieCrates.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MigrationRunner {
    private static final String INDEX_RESOURCE = "db/migrations.index";

    void migrate(Connection connection) throws SQLException {
        createVersionTable(connection);
        List<Migration> migrations = loadMigrations();
        Set<Integer> applied = appliedVersions(connection);
        int newestKnown = migrations.stream().mapToInt(Migration::version).max().orElse(0);
        int newestApplied = applied.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (newestApplied > newestKnown) {
            throw new SQLException("Database schema version " + newestApplied
                    + " is newer than this plugin supports (" + newestKnown + ")");
        }
        for (Migration migration : migrations) {
            if (!applied.contains(migration.version())) {
                apply(connection, migration);
            }
        }
    }

    private void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        resource TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private Set<Integer> appliedVersions(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM schema_version")) {
            while (result.next()) {
                versions.add(result.getInt(1));
            }
        }
        return versions;
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeScript(connection, readResource(migration.resource()));
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_version(version, resource, applied_at) VALUES (?, ?, ?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.resource());
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void executeScript(Connection connection, String script) throws SQLException {
        for (String sql : splitStatements(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException exception) {
                throw new SQLException("Migration statement failed: " + abbreviate(sql), exception);
            }
        }
    }

    private List<Migration> loadMigrations() throws SQLException {
        String index = readResource(INDEX_RESOURCE);
        List<Migration> migrations = new ArrayList<>();
        Set<Integer> versions = new HashSet<>();
        for (String line : index.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new SQLException("Invalid migration index line: " + line);
            }
            int version;
            try {
                version = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException exception) {
                throw new SQLException("Invalid migration version: " + parts[0], exception);
            }
            if (version <= 0 || !versions.add(version)) {
                throw new SQLException("Duplicate or invalid migration version: " + version);
            }
            migrations.add(new Migration(version, parts[1].trim()));
        }
        migrations.sort(Comparator.comparingInt(Migration::version));
        return List.copyOf(migrations);
    }

    private String readResource(String resource) throws SQLException {
        ClassLoader loader = MigrationRunner.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SQLException("Missing database migration resource: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
                return content.toString();
            }
        } catch (IOException exception) {
            throw new SQLException("Could not read database migration resource: " + resource, exception);
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                    current.append(ch);
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && ch == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (ch == '\'' && !doubleQuoted) {
                current.append(ch);
                if (singleQuoted && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    singleQuoted = !singleQuoted;
                }
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                current.append(ch);
                continue;
            }
            if (ch == ';' && !singleQuoted && !doubleQuoted) {
                addStatement(statements, current);
                continue;
            }
            current.append(ch);
        }
        addStatement(statements, current);
        return List.copyOf(statements);
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String sql = current.toString().trim();
        current.setLength(0);
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
    }

    private static String abbreviate(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    private record Migration(int version, String resource) {
    }
}
