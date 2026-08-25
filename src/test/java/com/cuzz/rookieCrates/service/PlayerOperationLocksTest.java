package com.cuzz.rookieCrates.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOperationLocksTest {

    @Test
    void safeHelperUnlocksEverySynchronousAndAsynchronousTerminalPath() {
        PlayerOperationLocks locks = new PlayerOperationLocks();
        UUID player = UUID.randomUUID();

        assertThrows(RejectedExecutionException.class, () -> locks.tryRunLocked(player, () -> {
            throw new RejectedExecutionException("scheduler stopped");
        }));
        assertFalse(locks.isLocked(player));

        assertThrows(NullPointerException.class, () -> locks.tryRunLocked(player, () -> null));
        assertFalse(locks.isLocked(player));

        CompletableFuture<String> failed = new CompletableFuture<>();
        Optional<CompletableFuture<String>> accepted = locks.tryRunLocked(player, () -> failed);
        assertTrue(accepted.isPresent());
        assertTrue(locks.isLocked(player));
        failed.completeExceptionally(new IllegalStateException("failed"));
        assertFalse(locks.isLocked(player));

        CompletableFuture<String> cancelled = new CompletableFuture<>();
        assertTrue(locks.tryRunLocked(player, () -> cancelled).isPresent());
        cancelled.cancel(false);
        assertFalse(locks.isLocked(player));

        assertTrue(locks.tryRunLocked(player, () -> CompletableFuture.completedFuture("ok")).isPresent());
        assertFalse(locks.isLocked(player));

        assertTrue(locks.tryStartLocked(player, () -> "handed-off").isPresent());
        assertTrue(locks.isLocked(player), "tryStartLocked hands successful ownership to its caller");
        locks.unlock(player);
    }

    @Test
    void maintenanceAndPlayerLockAreMutuallyExclusive() {
        PlayerOperationLocks locks = new PlayerOperationLocks();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(locks.tryLock(first));
        assertFalse(locks.tryBeginMaintenance());
        locks.unlock(first);

        assertTrue(locks.tryBeginMaintenance());
        assertTrue(locks.isMaintenanceActive());
        assertFalse(locks.tryBeginMaintenance());
        assertFalse(locks.tryLock(second));
        locks.endMaintenance();
        assertFalse(locks.isMaintenanceActive());
        assertTrue(locks.tryLock(second));
        locks.unlock(second);
    }

    @Test
    void concurrentMaintenanceAndPlayerStartCannotBothWin() throws Exception {
        PlayerOperationLocks locks = new PlayerOperationLocks();
        UUID player = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> playerResult = executor.submit(() -> {
                    start.await();
                    return locks.tryLock(player);
                });
                Future<Boolean> maintenanceResult = executor.submit(() -> {
                    start.await();
                    return locks.tryBeginMaintenance();
                });

                boolean playerWon = get(playerResult);
                boolean maintenanceWon = get(maintenanceResult);
                assertTrue(playerWon ^ maintenanceWon,
                        "exactly one mutually exclusive operation must enter");

                if (playerWon) {
                    locks.unlock(player);
                } else {
                    locks.endMaintenance();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean get(Future<Boolean> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }
}
