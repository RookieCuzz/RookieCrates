package com.cuzz.rookieCrates.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class PlayerOperationLocks {

    /*
     * The maintenance flag and player set deliberately share one monitor.  Keeping the
     * "no players -> enter maintenance" check in the same critical section as tryLock
     * prevents a player operation from slipping through between the check and flag write.
     */
    private final Object monitor = new Object();
    private final Set<UUID> locked = new HashSet<>();
    private boolean maintenance;

    public boolean tryLock(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        synchronized (monitor) {
            return !maintenance && locked.add(playerUuid);
        }
    }

    public void unlock(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        synchronized (monitor) {
            locked.remove(playerUuid);
        }
    }

    public boolean isLocked(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        synchronized (monitor) {
            return locked.contains(playerUuid);
        }
    }

    /**
     * Atomically enters global maintenance only when no player operation is active.
     * While maintenance is active every new {@link #tryLock(UUID)} call is rejected.
     */
    public boolean tryBeginMaintenance() {
        synchronized (monitor) {
            if (maintenance || !locked.isEmpty()) {
                return false;
            }
            maintenance = true;
            return true;
        }
    }

    public void endMaintenance() {
        synchronized (monitor) {
            maintenance = false;
        }
    }

    public boolean isMaintenanceActive() {
        synchronized (monitor) {
            return maintenance;
        }
    }

    /**
     * Acquires the player lock and safely constructs an operation.  If construction throws
     * synchronously (including an executor rejection), the lock is released before rethrowing.
     * On success ownership is handed to the caller, which must eventually call {@link #unlock(UUID)}.
     * An empty result means the lock was unavailable.
     */
    public <T> Optional<T> tryStartLocked(UUID playerUuid, Supplier<? extends T> operation) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(operation, "operation");
        if (!tryLock(playerUuid)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Objects.requireNonNull(operation.get(), "operation returned null"));
        } catch (RuntimeException | Error failure) {
            unlock(playerUuid);
            throw failure;
        }
    }

    /**
     * Starts an asynchronous player operation under a lock and releases that lock on every
     * terminal path, including a synchronous supplier failure, executor rejection, null future,
     * exceptional completion, and cancellation.  An empty result means the lock was unavailable.
     *
     * <p>The returned future is the operation's original future; callers must not also unlock it.</p>
     */
    public <T> Optional<CompletableFuture<T>> tryRunLocked(
            UUID playerUuid,
            Supplier<? extends CompletableFuture<T>> operation
    ) {
        Optional<CompletableFuture<T>> started = tryStartLocked(playerUuid, operation);
        if (started.isEmpty()) {
            return Optional.empty();
        }

        try {
            CompletableFuture<T> future = started.get();
            future.whenComplete((ignored, failure) -> unlock(playerUuid));
            return Optional.of(future);
        } catch (RuntimeException | Error failure) {
            unlock(playerUuid);
            throw failure;
        }
    }
}
