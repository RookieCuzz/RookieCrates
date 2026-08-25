package com.cuzz.rookieCrates.runtime.recovery;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implemented by the SQLite adapter owned by the plugin bootstrap/storage layer.
 * Every method must enqueue database work and return promptly; it must never perform
 * blocking JDBC work before returning to its Bukkit-main-thread caller.
 */
public interface SceneRecoveryStore {
    CompletableFuture<Void> save(SceneRecovery recovery);

    CompletableFuture<Optional<SceneRecovery>> find(UUID playerId);

    CompletableFuture<Collection<SceneRecovery>> findAll();

    /** Makes an already-restored snapshot invisible before best-effort physical deletion. */
    CompletableFuture<Void> markApplied(UUID playerId, UUID transactionId);

    /** Deletes only the expected transaction so a stale continuation cannot remove a newer row. */
    CompletableFuture<Void> delete(UUID playerId, UUID transactionId);
}
