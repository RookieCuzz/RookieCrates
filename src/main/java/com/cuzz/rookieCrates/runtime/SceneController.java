package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.runtime.recovery.SceneRecovery;
import com.cuzz.rookieCrates.runtime.recovery.SceneRecoveryStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread coordinator for isolated per-player scene sessions and asynchronous crash recovery. */
public final class SceneController {
    private static final long[] RETIRE_RETRY_DELAYS_TICKS = {20L, 60L};

    private final Plugin plugin;
    private final CrateRuntime crateRuntime;
    private final SceneRecoveryStore recoveryStore;
    private final SceneTiming timing;
    private final RecordedCameraBridge recordedCameraBridge;
    private final Map<UUID, SceneSession> sessions = new HashMap<>();
    private final Map<UUID, PendingStart> pendingStarts = new HashMap<>();
    private final Set<UUID> pendingRecoveryReads = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingDeathRecovery> pendingDeaths = new HashMap<>();
    private final Set<RecoveryKey> retiredRecoveries = ConcurrentHashMap.newKeySet();
    private final Set<RecoveryKey> retirementInFlight = ConcurrentHashMap.newKeySet();

    public SceneController(Plugin plugin, CrateRuntime crateRuntime, SceneRecoveryStore recoveryStore) {
        this(plugin, crateRuntime, recoveryStore, SceneTiming.DEFAULT, null);
    }

    public SceneController(
            Plugin plugin,
            CrateRuntime crateRuntime,
            SceneRecoveryStore recoveryStore,
            SceneTiming timing
    ) {
        this(plugin, crateRuntime, recoveryStore, timing, null);
    }

    public SceneController(
            Plugin plugin,
            CrateRuntime crateRuntime,
            SceneRecoveryStore recoveryStore,
            SceneTiming timing,
            RecordedCameraBridge recordedCameraBridge
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.crateRuntime = Objects.requireNonNull(crateRuntime, "crateRuntime");
        this.recoveryStore = Objects.requireNonNull(recoveryStore, "recoveryStore");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.recordedCameraBridge = recordedCameraBridge;
    }

    /**
     * Accepts a scene request. The snapshot is captured now, persisted asynchronously, and only
     * after persistence succeeds is scene mutation scheduled back onto the Bukkit main thread.
     */
    public boolean play(Player player, SceneRequest request) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || isBusy(playerId)) {
            return false;
        }

        PlayerStateSnapshot snapshot = PlayerStateSnapshot.capture(player);
        SceneRecovery recovery = snapshot.toRecovery(playerId, request.transactionId());
        PendingStart pending = new PendingStart(snapshot, request);
        pendingStarts.put(playerId, pending);

        final CompletableFuture<Void> persistence;
        try {
            persistence = requireFuture(recoveryStore.save(recovery), "SceneRecoveryStore.save");
            pending.persistence = persistence;
        } catch (RuntimeException exception) {
            pendingStarts.remove(playerId);
            invokeFailure(request, exception);
            return false;
        }

        persistence.whenComplete((ignored, failure) -> {
            Throwable unwrapped = unwrap(failure);
            if (pending.cancelled) {
                if (unwrapped == null) {
                    retireRecovery(playerId, pending.request.transactionId());
                }
                return;
            }
            if (!scheduleMain(() -> finishPersistedStart(playerId, pending, unwrapped)) && unwrapped == null) {
                retireRecovery(playerId, pending.request.transactionId());
            }
        });
        return true;
    }

    public boolean hasActiveSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public boolean isBusy(UUID playerId) {
        return sessions.containsKey(playerId)
                || pendingStarts.containsKey(playerId)
                || pendingDeaths.containsKey(playerId)
                || pendingRecoveryReads.contains(playerId);
    }

    /** Called from PlayerToggleSneakEvent; an initial sneaking click never reaches this path. */
    public boolean skip(Player player) {
        requireMainThread();
        SceneSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.canSkip()) {
            return false;
        }
        complete(player.getUniqueId());
        return true;
    }

    void reveal(UUID playerId) {
        requireMainThread();
        SceneSession session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        try {
            session.reveal();
        } catch (RuntimeException exception) {
            abort(playerId, SceneAbortReason.INTERNAL_ERROR, exception);
        }
    }

    void complete(UUID playerId) {
        requireMainThread();
        SceneSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        completeSession(session);
    }

    void completeRecorded(SceneSession expected) {
        requireMainThread();
        if (!sessions.remove(expected.playerId(), expected)) {
            return;
        }
        completeSession(expected);
    }

    void abortRecorded(SceneSession expected, Throwable cause) {
        requireMainThread();
        if (!sessions.remove(expected.playerId(), expected)) {
            return;
        }
        finishAbort(expected.playerId(), expected, SceneAbortReason.CAMERA_FAILURE, cause);
    }

    private void completeSession(SceneSession session) {
        session.closeResources();
        UUID playerId = session.playerId();
        Player player = Bukkit.getPlayer(playerId);
        boolean restored = player != null && session.snapshot().restore(player, true);
        if (!restored) {
            IllegalStateException restoreFailure =
                    new IllegalStateException("Player state could not be fully restored");
            Throwable failure = session.request().serverToursRoute() == null
                    ? restoreFailure
                    : new SceneAbortedException(SceneAbortReason.CAMERA_FAILURE, restoreFailure);
            invokeFailure(session.request(), failure);
            return;
        }
        retireRecovery(playerId, session.request().transactionId());
        invokeComplete(session.request());
    }

    public void abort(Player player, SceneAbortReason reason) {
        requireMainThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        UUID playerId = player.getUniqueId();
        PendingStart pending = pendingStarts.remove(playerId);
        if (pending != null) {
            pending.cancelled = true;
            deleteAfterPersistence(playerId, pending);
            invokeFailure(pending.request, new SceneAbortedException(reason));
        }
        abort(playerId, reason, new SceneAbortedException(reason));
    }

    public void handleDeath(Player player) {
        requireMainThread();
        UUID playerId = player.getUniqueId();
        PendingStart pendingStart = pendingStarts.remove(playerId);
        if (pendingStart != null) {
            pendingStart.cancelled = true;
            deleteAfterPersistence(playerId, pendingStart);
            invokeFailure(pendingStart.request, new SceneAbortedException(SceneAbortReason.DEATH));
        }
        SceneSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        session.closeResources();
        session.snapshot().restore(player, false);
        pendingDeaths.put(playerId, new PendingDeathRecovery(
                session.snapshot(), session.request().transactionId()));
        invokeFailure(session.request(), new SceneAbortedException(SceneAbortReason.DEATH));
    }

    public void handleRespawn(PlayerRespawnEvent event) {
        requireMainThread();
        UUID playerId = event.getPlayer().getUniqueId();
        PendingDeathRecovery pending = pendingDeaths.get(playerId);
        if (pending == null) {
            return;
        }
        Location recoveryLocation = pending.snapshot().recoveryLocation();
        if (recoveryLocation != null) {
            event.setRespawnLocation(recoveryLocation);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> finishDeathRecovery(event.getPlayer()));
    }

    /** Starts a non-blocking lookup; true means accepted, not that a recovery row existed. */
    public boolean recoverPending(Player player) {
        requireMainThread();
        UUID playerId = player.getUniqueId();
        if (isBusy(playerId)) {
            return false;
        }
        pendingRecoveryReads.add(playerId);
        final CompletableFuture<Optional<SceneRecovery>> lookup;
        try {
            lookup = requireFuture(recoveryStore.find(playerId), "SceneRecoveryStore.find");
        } catch (RuntimeException exception) {
            pendingRecoveryReads.remove(playerId);
            logRecoveryFailure(player, exception);
            return false;
        }
        lookup.whenComplete((stored, failure) -> {
            if (!scheduleMain(() -> finishRecoveryLookup(playerId, stored, unwrap(failure)))) {
                pendingRecoveryReads.remove(playerId);
            }
        });
        return true;
    }

    public void recoverOnlinePlayers() {
        requireMainThread();
        for (Player player : Bukkit.getOnlinePlayers()) {
            recoverPending(player);
        }
    }

    /** Idempotently removes live resources and never waits for SQLite futures. */
    public void shutdown() {
        requireMainThread();
        pendingStarts.entrySet().stream().toList().forEach(entry -> {
            PendingStart pending = pendingStarts.remove(entry.getKey());
            pending.cancelled = true;
            deleteAfterPersistence(entry.getKey(), pending);
            invokeFailure(pending.request, new SceneAbortedException(SceneAbortReason.DISABLE));
        });
        sessions.keySet().stream().toList().forEach(playerId ->
                abort(playerId, SceneAbortReason.DISABLE, new SceneAbortedException(SceneAbortReason.DISABLE))
        );
        pendingDeaths.entrySet().stream().toList().forEach(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && entry.getValue().snapshot().restore(player, true)) {
                retireRecovery(entry.getKey(), entry.getValue().transactionId());
                pendingDeaths.remove(entry.getKey());
            }
        });
        pendingRecoveryReads.clear();
    }

    private void finishPersistedStart(UUID playerId, PendingStart expected, Throwable failure) {
        requireMainThread();
        PendingStart current = pendingStarts.get(playerId);
        if (current != expected || expected.cancelled) {
            if (failure == null) {
                retireRecovery(playerId, expected.request.transactionId());
            }
            return;
        }
        pendingStarts.remove(playerId);
        if (failure != null) {
            invokeFailure(expected.request, failure);
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead() || sessions.containsKey(playerId)) {
            retireRecovery(playerId, expected.request.transactionId());
            invokeFailure(expected.request, new IllegalStateException("Player became unavailable before scene start"));
            return;
        }

        SceneSession session = new SceneSession(
                plugin,
                crateRuntime,
                this,
                player,
                expected.request,
                expected.snapshot,
                timing,
                recordedCameraBridge
        );
        sessions.put(playerId, session);
        try {
            session.start();
        } catch (RuntimeException exception) {
            sessions.remove(playerId);
            session.closeResources();
            boolean restored = expected.snapshot.restore(player, true);
            if (restored) {
                retireRecovery(playerId, expected.request.transactionId());
            }
            invokeFailure(expected.request, exception);
        }
    }

    private void finishRecoveryLookup(UUID playerId, Optional<SceneRecovery> stored, Throwable failure) {
        requireMainThread();
        if (!pendingRecoveryReads.remove(playerId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (failure != null) {
            if (player != null) {
                logRecoveryFailure(player, failure);
            }
            return;
        }
        if (stored == null || stored.isEmpty() || player == null || !player.isOnline()) {
            return;
        }
        SceneRecovery recovery = stored.get();
        RecoveryKey recoveryKey = new RecoveryKey(playerId, recovery.transactionId());
        if (retiredRecoveries.contains(recoveryKey)) {
            // A prior successful in-process restore reached its external side effect but the
            // APPLIED write was not yet durable. Never apply that same snapshot twice here.
            retireRecovery(playerId, recovery.transactionId());
            return;
        }
        try {
            PlayerStateSnapshot snapshot = PlayerStateSnapshot.fromRecovery(recovery);
            if (!snapshot.restore(player, true)) {
                plugin.getLogger().severe("Could not fully restore scene state for " + player.getName());
                return;
            }
            pendingDeaths.remove(playerId);
            retireRecovery(playerId, recovery.transactionId());
        } catch (RuntimeException exception) {
            logRecoveryFailure(player, exception);
        }
    }

    private void abort(UUID playerId, SceneAbortReason reason, Throwable cause) {
        SceneSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        finishAbort(playerId, session, reason, cause);
    }

    private void finishAbort(UUID playerId, SceneSession session, SceneAbortReason reason, Throwable cause) {
        session.closeResources();
        Player player = Bukkit.getPlayer(playerId);
        boolean restored = player != null && session.snapshot().restore(player, true);
        if (restored) {
            retireRecovery(playerId, session.request().transactionId());
        } else {
            plugin.getLogger().severe("Could not fully restore aborted scene for player " + playerId
                    + " (" + reason + ")");
        }
        invokeFailure(session.request(), cause);
    }

    private void finishDeathRecovery(Player player) {
        if (!player.isOnline()) {
            return;
        }
        PendingDeathRecovery pending = pendingDeaths.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        if (pending.snapshot().restore(player, true)) {
            pendingDeaths.remove(player.getUniqueId());
            retireRecovery(player.getUniqueId(), pending.transactionId());
        }
    }

    private void deleteAfterPersistence(UUID playerId, PendingStart pending) {
        CompletableFuture<Void> persistence = pending.persistence;
        if (persistence == null) {
            return;
        }
        persistence.whenComplete((ignored, failure) -> {
            if (failure == null) {
                retireRecovery(playerId, pending.request.transactionId());
            }
        });
    }

    /**
     * Persists APPLIED before best-effort deletion.  A failed delete therefore leaves an
     * intentionally invisible tombstone instead of a snapshot that can restore twice.
     */
    private void retireRecovery(UUID playerId, UUID transactionId) {
        RecoveryKey key = new RecoveryKey(playerId, transactionId);
        retiredRecoveries.add(key);
        if (!retirementInFlight.add(key)) {
            return;
        }
        attemptRetireRecovery(key, 0);
    }

    private void attemptRetireRecovery(RecoveryKey key, int attempt) {
        try {
            requireFuture(
                    recoveryStore.markApplied(key.playerId(), key.transactionId()),
                    "SceneRecoveryStore.markApplied"
            ).whenComplete((ignored, failure) -> {
                Throwable unwrapped = unwrap(failure);
                if (unwrapped == null) {
                    deleteAppliedRecovery(key);
                } else {
                    retryRetirement(key, attempt, unwrapped);
                }
            });
        } catch (RuntimeException exception) {
            retryRetirement(key, attempt, exception);
        }
    }

    private void deleteAppliedRecovery(RecoveryKey key) {
        try {
            requireFuture(
                    recoveryStore.delete(key.playerId(), key.transactionId()),
                    "SceneRecoveryStore.delete"
            ).whenComplete((ignored, failure) -> {
                retirementInFlight.remove(key);
                Throwable unwrapped = unwrap(failure);
                if (unwrapped != null) {
                    plugin.getLogger().severe("APPLIED scene recovery could not be deleted for "
                            + key.playerId() + " transaction " + key.transactionId() + ": "
                            + unwrapped.getMessage() + ". It remains durably hidden from recovery lookups.");
                }
            });
        } catch (RuntimeException failure) {
            retirementInFlight.remove(key);
            plugin.getLogger().severe("APPLIED scene recovery deletion could not be scheduled for "
                    + key.playerId() + " transaction " + key.transactionId() + ": "
                    + failure.getMessage() + ". It remains durably hidden from recovery lookups.");
        }
    }

    private void retryRetirement(RecoveryKey key, int attempt, Throwable failure) {
        if (!plugin.isEnabled() || attempt >= RETIRE_RETRY_DELAYS_TICKS.length) {
            retirementInFlight.remove(key);
            plugin.getLogger().severe("Could not persist APPLIED scene recovery for " + key.playerId()
                    + " transaction " + key.transactionId() + " after " + (attempt + 1)
                    + " attempt(s): " + failure.getMessage()
                    + ". The in-process guard remains active; if SQLite is closing, the ACTIVE row "
                    + "will be retried as an at-least-once recovery after restart.");
            return;
        }

        long delay = RETIRE_RETRY_DELAYS_TICKS[attempt];
        try {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> attemptRetireRecovery(key, attempt + 1),
                    delay
            );
        } catch (RuntimeException schedulingFailure) {
            retirementInFlight.remove(key);
            schedulingFailure.addSuppressed(failure);
            plugin.getLogger().severe("Could not schedule APPLIED recovery retry for " + key.playerId()
                    + " transaction " + key.transactionId() + ": " + schedulingFailure.getMessage()
                    + ". The in-process guard remains active; an ACTIVE row left during shutdown "
                    + "uses at-least-once recovery after restart.");
        }
    }

    private boolean scheduleMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return false;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not schedule scene continuation: " + exception.getMessage());
            return false;
        }
    }

    private void logRecoveryFailure(Player player, Throwable failure) {
        plugin.getLogger().severe("Could not read scene recovery for " + player.getName()
                + ": " + failure.getMessage());
    }

    private void invokeComplete(SceneRequest request) {
        try {
            request.onComplete().run();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Scene completion callback failed for transaction "
                    + request.transactionId() + ": " + exception.getMessage());
        }
    }

    private void invokeFailure(SceneRequest request, Throwable failure) {
        try {
            request.onFailure().accept(failure);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Scene failure callback failed for transaction "
                    + request.transactionId() + ": " + exception.getMessage());
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static <T> CompletableFuture<T> requireFuture(CompletableFuture<T> future, String operation) {
        return Objects.requireNonNull(future, operation + " returned null");
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("SceneController must be called on the Bukkit main thread");
        }
    }

    private static final class PendingStart {
        private final PlayerStateSnapshot snapshot;
        private final SceneRequest request;
        private volatile boolean cancelled;
        private CompletableFuture<Void> persistence;

        private PendingStart(PlayerStateSnapshot snapshot, SceneRequest request) {
            this.snapshot = snapshot;
            this.request = request;
        }
    }

    private record PendingDeathRecovery(PlayerStateSnapshot snapshot, UUID transactionId) { }

    private record RecoveryKey(UUID playerId, UUID transactionId) {
        private RecoveryKey {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(transactionId, "transactionId");
        }
    }
}
