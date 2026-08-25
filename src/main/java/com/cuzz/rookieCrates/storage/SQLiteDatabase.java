package com.cuzz.rookieCrates.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the single SQLite connection and its single worker thread.
 * Bukkit API calls must never be made inside submitted database work.
 */
public final class SQLiteDatabase implements AutoCloseable {
    private final Path databaseFile;
    private final int busyTimeoutMillis;
    private final AtomicReference<Thread> databaseThread = new AtomicReference<>();
    private final ExecutorService executor;
    private volatile boolean started;
    private volatile boolean accepting;
    private Connection connection;
    private RookieCratesDao dao;

    public SQLiteDatabase(Path databaseFile) {
        this(databaseFile, 5_000);
    }

    public SQLiteDatabase(Path databaseFile, int busyTimeoutMillis) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
        if (busyTimeoutMillis < 1) {
            throw new IllegalArgumentException("busyTimeoutMillis must be positive");
        }
        this.busyTimeoutMillis = busyTimeoutMillis;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "RookieCrates-SQLite");
            thread.setDaemon(false);
            databaseThread.set(thread);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /** Starts SQLite, configures its safety pragmas, and applies all pending migrations. */
    public synchronized void start() throws SQLException {
        if (started) {
            return;
        }
        if (executor.isShutdown()) {
            throw new IllegalStateException("Database has already been closed");
        }
        try {
            executor.submit(() -> {
                openOnDatabaseThread();
                return null;
            }).get();
            started = true;
            accepting = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            shutdownAfterStartFailure();
            throw new SQLException("Interrupted while starting SQLite", exception);
        } catch (ExecutionException exception) {
            shutdownAfterStartFailure();
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Could not start SQLite", cause);
        }
    }

    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * Returns the synchronous DAO. Its methods intentionally fail when called outside this database's worker thread.
     * Prefer {@link #submit(SqlFunction)} or {@link #transaction(SqlFunction)}.
     */
    public RookieCratesDao dao() {
        ensureStarted();
        return dao;
    }

    public <T> CompletableFuture<T> submit(SqlFunction<T> task) {
        Objects.requireNonNull(task, "task");
        ensureAccepting();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.apply(dao);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> run(SqlConsumer task) {
        Objects.requireNonNull(task, "task");
        return submit(dao -> {
            task.accept(dao);
            return null;
        });
    }

    /** Runs all DAO calls in the callback in one SQLite transaction. */
    public <T> CompletableFuture<T> transaction(SqlFunction<T> task) {
        Objects.requireNonNull(task, "task");
        return submit(ignored -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            if (!previousAutoCommit) {
                return task.apply(dao);
            }
            connection.setAutoCommit(false);
            try {
                T result = task.apply(dao);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public boolean isDatabaseThread() {
        return Thread.currentThread() == databaseThread.get();
    }

    private void openOnDatabaseThread() throws SQLException {
        try {
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new SQLException("Could not create database directory for " + databaseFile, exception);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            configure(connection);
            new MigrationRunner().migrate(connection);
            dao = new RookieCratesDao(connection, this::isDatabaseThread);
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            connection = null;
            throw exception;
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            statement.execute("PRAGMA synchronous=NORMAL");
        }
    }

    private void ensureStarted() {
        if (!started || dao == null) {
            throw new IllegalStateException("Database has not been started");
        }
    }

    private void ensureAccepting() {
        ensureStarted();
        if (!accepting) {
            throw new IllegalStateException("Database is closing");
        }
    }

    private void shutdownAfterStartFailure() {
        accepting = false;
        started = false;
        executor.shutdownNow();
    }

    @Override
    public synchronized void close() {
        if (executor.isShutdown()) {
            return;
        }
        accepting = false;
        boolean calledFromDatabaseThread = isDatabaseThread();
        if (started && connection != null) {
            if (calledFromDatabaseThread) {
                closeConnectionUnchecked();
            } else {
                try {
                    executor.submit(this::closeConnectionUnchecked).get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    throw new DatabaseException("Could not close SQLite", exception.getCause());
                }
            }
        }
        started = false;
        executor.shutdown();
        if (calledFromDatabaseThread) {
            return;
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void closeConnectionUnchecked() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new DatabaseException("Could not close SQLite connection", exception);
        } finally {
            connection = null;
            dao = null;
        }
    }
}
