package com.cuzz.rookieCrates.storage;

import com.cuzz.rookieCrates.domain.ActiveSceneRecovery;
import com.cuzz.rookieCrates.runtime.recovery.SceneRecovery;
import com.cuzz.rookieCrates.runtime.recovery.SceneRecoveryStore;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Async adapter between the scene runtime and the authoritative SQLite recovery table. */
public final class SQLiteSceneRecoveryStore implements SceneRecoveryStore {
    private final SQLiteDatabase database;

    public SQLiteSceneRecoveryStore(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<Void> save(SceneRecovery recovery) {
        Objects.requireNonNull(recovery, "recovery");
        return database.run(dao -> dao.saveActiveSceneRecovery(toEntity(recovery)));
    }

    @Override
    public CompletableFuture<Optional<SceneRecovery>> find(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.submit(dao -> dao.findActiveSceneRecovery(playerId).map(this::toRuntime));
    }

    @Override
    public CompletableFuture<Collection<SceneRecovery>> findAll() {
        return database.submit(dao -> dao.listActiveSceneRecoveries().stream()
                .map(this::toRuntime)
                .map(SceneRecovery.class::cast)
                .toList());
    }

    @Override
    public CompletableFuture<Void> markApplied(UUID playerId, UUID transactionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(transactionId, "transactionId");
        return database.run(dao -> dao.markActiveSceneRecoveryApplied(
                playerId, transactionId, System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<Void> delete(UUID playerId, UUID transactionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(transactionId, "transactionId");
        return database.run(dao -> dao.deleteActiveSceneRecovery(playerId, transactionId));
    }

    private ActiveSceneRecovery toEntity(SceneRecovery recovery) {
        return new ActiveSceneRecovery(
                recovery.playerId(),
                recovery.transactionId(),
                UUID.fromString(recovery.worldId()),
                recovery.worldName(),
                recovery.x(),
                recovery.y(),
                recovery.z(),
                recovery.yaw(),
                recovery.pitch(),
                recovery.gameMode(),
                recovery.allowFlight(),
                recovery.flying(),
                recovery.walkSpeed(),
                recovery.flySpeed(),
                recovery.invulnerable(),
                recovery.collidable(),
                nullableUuid(recovery.spectatorTargetId()),
                System.currentTimeMillis()
        );
    }

    private SceneRecovery toRuntime(ActiveSceneRecovery recovery) {
        return new SceneRecovery(
                recovery.playerUuid(),
                recovery.transactionId(),
                recovery.worldUuid().toString(),
                recovery.world(),
                recovery.x(),
                recovery.y(),
                recovery.z(),
                recovery.yaw(),
                recovery.pitch(),
                recovery.gameMode(),
                recovery.allowFlight(),
                recovery.flying(),
                recovery.flySpeed(),
                recovery.walkSpeed(),
                recovery.invulnerable(),
                recovery.collidable(),
                recovery.cameraEntityUuid() == null ? null : recovery.cameraEntityUuid().toString()
        );
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
