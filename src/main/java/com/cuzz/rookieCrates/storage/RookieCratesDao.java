package com.cuzz.rookieCrates.storage;

import com.cuzz.rookieCrates.domain.ActiveSceneRecovery;
import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.OpenResult;
import com.cuzz.rookieCrates.domain.OpenTransaction;
import com.cuzz.rookieCrates.domain.OpenTransactionStatus;
import com.cuzz.rookieCrates.domain.PendingDelivery;
import com.cuzz.rookieCrates.domain.PendingDeliveryStatus;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.PlayerCrateState;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardCommand;
import com.cuzz.rookieCrates.domain.RewardDefinition;
import com.cuzz.rookieCrates.domain.RewardItem;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Synchronous SQLite data access. Every public method must run inside
 * {@link SQLiteDatabase#submit(SqlFunction)} or {@link SQLiteDatabase#transaction(SqlFunction)}.
 */
public final class RookieCratesDao {
    private final Connection connection;
    private final BooleanSupplier onDatabaseThread;

    RookieCratesDao(Connection connection, BooleanSupplier onDatabaseThread) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.onDatabaseThread = Objects.requireNonNull(onDatabaseThread, "onDatabaseThread");
    }

    public int schemaVersion() throws SQLException {
        checkThread();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public Optional<CrateDefinition> findCrate(String id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM crates WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readCrate(result)) : Optional.empty();
            }
        }
    }

    public List<CrateDefinition> listCrates() throws SQLException {
        checkThread();
        List<CrateDefinition> crates = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM crates ORDER BY id")) {
            while (result.next()) {
                crates.add(readCrate(result));
            }
        }
        return List.copyOf(crates);
    }

    public void upsertCrate(CrateDefinition crate) throws SQLException {
        checkThread();
        Objects.requireNonNull(crate, "crate");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crates(
                    id, display_name, enabled, key_item_blob, single_price, seven_price,
                    guarantee_a, guarantee_s, scene_profile_id, broadcast_rarity,
                    skip_allowed, interaction_width, interaction_height, idle_animation,
                    single_open_animation, seven_open_animation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    display_name = excluded.display_name,
                    enabled = excluded.enabled,
                    key_item_blob = excluded.key_item_blob,
                    single_price = excluded.single_price,
                    seven_price = excluded.seven_price,
                    guarantee_a = excluded.guarantee_a,
                    guarantee_s = excluded.guarantee_s,
                    scene_profile_id = excluded.scene_profile_id,
                    broadcast_rarity = excluded.broadcast_rarity,
                    skip_allowed = excluded.skip_allowed,
                    interaction_width = excluded.interaction_width,
                    interaction_height = excluded.interaction_height,
                    idle_animation = excluded.idle_animation,
                    single_open_animation = excluded.single_open_animation,
                    seven_open_animation = excluded.seven_open_animation
                """)) {
            int index = 1;
            statement.setString(index++, crate.id());
            statement.setString(index++, crate.displayName());
            statement.setInt(index++, bool(crate.enabled()));
            setNullableBytes(statement, index++, crate.keyItemBlob());
            statement.setDouble(index++, crate.singlePrice());
            statement.setDouble(index++, crate.sevenPrice());
            statement.setInt(index++, crate.guaranteeA());
            statement.setInt(index++, crate.guaranteeS());
            setNullableString(statement, index++, crate.sceneProfileId());
            setNullableString(statement, index++, crate.broadcastRarity() == null
                    ? null : crate.broadcastRarity().name());
            statement.setInt(index++, bool(crate.skipAllowed()));
            statement.setDouble(index++, crate.interactionWidth());
            statement.setDouble(index++, crate.interactionHeight());
            statement.setString(index++, crate.idleAnimation());
            statement.setString(index++, crate.singleOpenAnimation());
            statement.setString(index, crate.sevenOpenAnimation());
            statement.executeUpdate();
        }
    }

    public boolean deleteCrate(String id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM crates WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<RewardBundle> findReward(String rewardId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM rewards WHERE id = ?")) {
            statement.setString(1, rewardId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                RewardDefinition definition = readReward(result);
                return Optional.of(bundle(definition));
            }
        }
    }

    public List<RewardBundle> listRewards(String crateId) throws SQLException {
        return listRewards(crateId, false);
    }

    public List<RewardBundle> listEnabledRewards(String crateId) throws SQLException {
        return listRewards(crateId, true);
    }

    private List<RewardBundle> listRewards(String crateId, boolean enabledOnly) throws SQLException {
        checkThread();
        String sql = "SELECT * FROM rewards WHERE crate_id = ?"
                + (enabledOnly ? " AND enabled = 1" : "")
                + " ORDER BY id";
        List<RewardBundle> rewards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, crateId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rewards.add(bundle(readReward(result)));
                }
            }
        }
        return List.copyOf(rewards);
    }

    public List<RewardBundle> listAllRewards() throws SQLException {
        checkThread();
        List<RewardBundle> rewards = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM rewards ORDER BY crate_id, id")) {
            while (result.next()) {
                rewards.add(bundle(readReward(result)));
            }
        }
        return List.copyOf(rewards);
    }

    public void upsertReward(RewardDefinition reward) throws SQLException {
        checkThread();
        Objects.requireNonNull(reward, "reward");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rewards(id, crate_id, display_name, description, rarity, weight, display_scale, enabled, broadcast)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    crate_id = excluded.crate_id,
                    display_name = excluded.display_name,
                    description = excluded.description,
                    rarity = excluded.rarity,
                    weight = excluded.weight,
                    display_scale = excluded.display_scale,
                    enabled = excluded.enabled,
                    broadcast = excluded.broadcast
                """)) {
            statement.setString(1, reward.id());
            statement.setString(2, reward.crateId());
            statement.setString(3, reward.displayName());
            statement.setString(4, reward.description());
            statement.setString(5, reward.rarity().name());
            statement.setDouble(6, reward.weight());
            statement.setDouble(7, reward.displayScale());
            statement.setInt(8, bool(reward.enabled()));
            statement.setInt(9, bool(reward.broadcast()));
            statement.executeUpdate();
        }
    }

    /** Replace the definition and all item/command children; call inside database.transaction for atomicity. */
    public void replaceRewardBundle(RewardBundle reward) throws SQLException {
        checkThread();
        upsertReward(reward.definition());
        deleteRewardItems(reward.definition().id());
        deleteRewardCommands(reward.definition().id());
        for (RewardItem item : reward.items()) {
            saveRewardItem(new RewardItem(0, item.rewardId(), item.itemBlob(), item.amount()));
        }
        for (RewardCommand command : reward.commands()) {
            saveRewardCommand(new RewardCommand(0, command.rewardId(), command.command(), command.executionOrder()));
        }
    }

    public boolean deleteReward(String rewardId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM rewards WHERE id = ?")) {
            statement.setString(1, rewardId);
            return statement.executeUpdate() > 0;
        }
    }

    public long saveRewardItem(RewardItem item) throws SQLException {
        checkThread();
        Objects.requireNonNull(item, "item");
        if (item.id() == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO reward_items(reward_id, item_blob, amount) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, item.rewardId());
                statement.setBytes(2, item.itemBlob());
                statement.setInt(3, item.amount());
                statement.executeUpdate();
                return generatedId(statement);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reward_items SET reward_id = ?, item_blob = ?, amount = ? WHERE id = ?")) {
            statement.setString(1, item.rewardId());
            statement.setBytes(2, item.itemBlob());
            statement.setInt(3, item.amount());
            statement.setLong(4, item.id());
            requireUpdated(statement.executeUpdate(), "reward item", item.id());
            return item.id();
        }
    }

    public boolean deleteRewardItem(long id) throws SQLException {
        checkThread();
        return deleteById("DELETE FROM reward_items WHERE id = ?", id);
    }

    public int deleteRewardItems(String rewardId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM reward_items WHERE reward_id = ?")) {
            statement.setString(1, rewardId);
            return statement.executeUpdate();
        }
    }

    public long saveRewardCommand(RewardCommand command) throws SQLException {
        checkThread();
        Objects.requireNonNull(command, "command");
        if (command.id() == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO reward_commands(reward_id, command, execution_order) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, command.rewardId());
                statement.setString(2, command.command());
                statement.setInt(3, command.executionOrder());
                statement.executeUpdate();
                return generatedId(statement);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE reward_commands SET reward_id = ?, command = ?, execution_order = ? WHERE id = ?")) {
            statement.setString(1, command.rewardId());
            statement.setString(2, command.command());
            statement.setInt(3, command.executionOrder());
            statement.setLong(4, command.id());
            requireUpdated(statement.executeUpdate(), "reward command", command.id());
            return command.id();
        }
    }

    public boolean deleteRewardCommand(long id) throws SQLException {
        checkThread();
        return deleteById("DELETE FROM reward_commands WHERE id = ?", id);
    }

    public int deleteRewardCommands(String rewardId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM reward_commands WHERE reward_id = ?")) {
            statement.setString(1, rewardId);
            return statement.executeUpdate();
        }
    }

    public Optional<Placement> findPlacement(String placementId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM placements WHERE placement_id = ?")) {
            statement.setString(1, placementId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readPlacement(result)) : Optional.empty();
            }
        }
    }

    public List<Placement> listPlacements(String crateId) throws SQLException {
        checkThread();
        List<Placement> placements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM placements WHERE crate_id = ? ORDER BY placement_id")) {
            statement.setString(1, crateId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    placements.add(readPlacement(result));
                }
            }
        }
        return List.copyOf(placements);
    }

    public List<Placement> listAllPlacements() throws SQLException {
        checkThread();
        List<Placement> placements = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM placements ORDER BY crate_id, placement_id")) {
            while (result.next()) {
                placements.add(readPlacement(result));
            }
        }
        return List.copyOf(placements);
    }

    public void upsertPlacement(Placement placement) throws SQLException {
        checkThread();
        Objects.requireNonNull(placement, "placement");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO placements(placement_id, crate_id, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(placement_id) DO UPDATE SET
                    crate_id = excluded.crate_id,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch
                """)) {
            statement.setString(1, placement.placementId());
            statement.setString(2, placement.crateId());
            statement.setString(3, placement.world());
            statement.setDouble(4, placement.x());
            statement.setDouble(5, placement.y());
            statement.setDouble(6, placement.z());
            statement.setFloat(7, placement.yaw());
            statement.setFloat(8, placement.pitch());
            statement.executeUpdate();
        }
    }

    public boolean deletePlacement(String placementId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM placements WHERE placement_id = ?")) {
            statement.setString(1, placementId);
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<SceneProfile> findSceneProfile(String id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM scene_profiles WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSceneProfile(result)) : Optional.empty();
            }
        }
    }

    public List<SceneProfile> listSceneProfiles() throws SQLException {
        checkThread();
        List<SceneProfile> profiles = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM scene_profiles ORDER BY id")) {
            while (result.next()) {
                profiles.add(readSceneProfile(result));
            }
        }
        return List.copyOf(profiles);
    }

    public void upsertSceneProfile(SceneProfile profile) throws SQLException {
        checkThread();
        Objects.requireNonNull(profile, "profile");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scene_profiles(id, name, crate_model, loot_model) VALUES (?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    crate_model = excluded.crate_model,
                    loot_model = excluded.loot_model
                """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.name());
            statement.setString(3, profile.crateModel());
            statement.setString(4, profile.lootModel());
            statement.executeUpdate();
        }
    }

    public boolean deleteSceneProfile(String id) throws SQLException {
        checkThread();
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE crates SET scene_profile_id = NULL WHERE scene_profile_id = ?")) {
            clear.setString(1, id);
            clear.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM scene_profiles WHERE id = ?")) {
            statement.setString(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    public List<ScenePoint> listScenePoints(String profileId) throws SQLException {
        checkThread();
        List<ScenePoint> points = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM scene_points WHERE profile_id = ?
                ORDER BY CASE kind WHEN 'CRATE' THEN 0 WHEN 'CAMERA' THEN 1 ELSE 2 END, point_index
                """)) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    points.add(readScenePoint(result));
                }
            }
        }
        return List.copyOf(points);
    }

    public List<ScenePoint> listAllScenePoints() throws SQLException {
        checkThread();
        List<ScenePoint> points = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM scene_points ORDER BY profile_id, kind, point_index")) {
            while (result.next()) {
                points.add(readScenePoint(result));
            }
        }
        return List.copyOf(points);
    }

    public void upsertScenePoint(ScenePoint point) throws SQLException {
        checkThread();
        Objects.requireNonNull(point, "point");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scene_points(profile_id, point_index, kind, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, kind, point_index) DO UPDATE SET
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch
                """)) {
            statement.setString(1, point.profileId());
            statement.setInt(2, point.pointIndex());
            statement.setString(3, point.kind().name());
            statement.setString(4, point.world());
            statement.setDouble(5, point.x());
            statement.setDouble(6, point.y());
            statement.setDouble(7, point.z());
            statement.setFloat(8, point.yaw());
            statement.setFloat(9, point.pitch());
            statement.executeUpdate();
        }
    }

    public boolean deleteScenePoint(String profileId, ScenePointKind kind, int pointIndex) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM scene_points WHERE profile_id = ? AND kind = ? AND point_index = ?")) {
            statement.setString(1, profileId);
            statement.setString(2, kind.name());
            statement.setInt(3, pointIndex);
            return statement.executeUpdate() > 0;
        }
    }

    public Optional<PlayerCrateState> findPlayerState(UUID playerUuid, String crateId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM player_crate_state WHERE player_uuid = ? AND crate_id = ?")) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, crateId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readPlayerState(result)) : Optional.empty();
            }
        }
    }

    public PlayerCrateState getOrCreatePlayerState(UUID playerUuid, String crateId, long now) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_crate_state(player_uuid, crate_id, virtual_keys, pity_a, pity_s, total_opens, updated_at)
                VALUES (?, ?, 0, 0, 0, 0, ?)
                ON CONFLICT(player_uuid, crate_id) DO NOTHING
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, crateId);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
        return findPlayerState(playerUuid, crateId)
                .orElseThrow(() -> new SQLException("Could not create player crate state"));
    }

    public List<PlayerCrateState> listPlayerStates(UUID playerUuid) throws SQLException {
        checkThread();
        List<PlayerCrateState> states = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM player_crate_state WHERE player_uuid = ? ORDER BY crate_id")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    states.add(readPlayerState(result));
                }
            }
        }
        return List.copyOf(states);
    }

    public List<PlayerCrateState> listAllPlayerStates() throws SQLException {
        checkThread();
        List<PlayerCrateState> states = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM player_crate_state ORDER BY player_uuid, crate_id")) {
            while (result.next()) {
                states.add(readPlayerState(result));
            }
        }
        return List.copyOf(states);
    }

    public void updatePlayerState(PlayerCrateState state) throws SQLException {
        checkThread();
        Objects.requireNonNull(state, "state");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_crate_state(
                    player_uuid, crate_id, virtual_keys, pity_a, pity_s, total_opens, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, crate_id) DO UPDATE SET
                    virtual_keys = excluded.virtual_keys,
                    pity_a = excluded.pity_a,
                    pity_s = excluded.pity_s,
                    total_opens = excluded.total_opens,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, state.playerUuid().toString());
            statement.setString(2, state.crateId());
            statement.setInt(3, state.virtualKeys());
            statement.setInt(4, state.pityA());
            statement.setInt(5, state.pityS());
            statement.setLong(6, state.totalOpens());
            statement.setLong(7, state.updatedAt());
            statement.executeUpdate();
        }
    }

    public void addVirtualKeys(UUID playerUuid, String crateId, int amount, long now) throws SQLException {
        checkThread();
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        getOrCreatePlayerState(playerUuid, crateId, now);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_crate_state
                SET virtual_keys = virtual_keys + ?, updated_at = ?
                WHERE player_uuid = ? AND crate_id = ?
                """)) {
            statement.setInt(1, amount);
            statement.setLong(2, now);
            statement.setString(3, playerUuid.toString());
            statement.setString(4, crateId);
            statement.executeUpdate();
        }
    }

    public boolean consumeVirtualKeys(UUID playerUuid, String crateId, int amount, long now) throws SQLException {
        checkThread();
        if (amount < 1 || amount > 7) {
            throw new IllegalArgumentException("amount must be in 1..7");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_crate_state
                SET virtual_keys = virtual_keys - ?, updated_at = ?
                WHERE player_uuid = ? AND crate_id = ? AND virtual_keys >= ?
                """)) {
            statement.setInt(1, amount);
            statement.setLong(2, now);
            statement.setString(3, playerUuid.toString());
            statement.setString(4, crateId);
            statement.setInt(5, amount);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deletePlayerState(UUID playerUuid, String crateId) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM player_crate_state WHERE player_uuid = ? AND crate_id = ?")) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, crateId);
            return statement.executeUpdate() > 0;
        }
    }

    public void createTransaction(OpenTransaction transaction) throws SQLException {
        checkThread();
        Objects.requireNonNull(transaction, "transaction");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO open_transactions(
                    id, player_uuid, crate_id, draw_count, status, created_at, completed_at, failure_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, transaction.id().toString());
            statement.setString(2, transaction.playerUuid().toString());
            statement.setString(3, transaction.crateId());
            statement.setInt(4, transaction.drawCount());
            statement.setString(5, transaction.status().name());
            statement.setLong(6, transaction.createdAt());
            setNullableLong(statement, 7, transaction.completedAt());
            setNullableString(statement, 8, transaction.failureReason());
            statement.executeUpdate();
        }
    }

    public Optional<OpenTransaction> findTransaction(UUID id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM open_transactions WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTransaction(result)) : Optional.empty();
            }
        }
    }

    public List<OpenTransaction> listPlayerTransactions(UUID playerUuid, int limit) throws SQLException {
        checkThread();
        requirePositiveLimit(limit);
        List<OpenTransaction> transactions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM open_transactions WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(readTransaction(result));
                }
            }
        }
        return List.copyOf(transactions);
    }

    public List<OpenTransaction> listTransactions(OpenTransactionStatus status, int limit) throws SQLException {
        checkThread();
        requirePositiveLimit(limit);
        List<OpenTransaction> transactions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM open_transactions WHERE status = ? ORDER BY created_at LIMIT ?
                """)) {
            statement.setString(1, status.name());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(readTransaction(result));
                }
            }
        }
        return List.copyOf(transactions);
    }

    public List<OpenTransaction> listAllTransactions() throws SQLException {
        checkThread();
        List<OpenTransaction> transactions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM open_transactions ORDER BY created_at, id")) {
            while (result.next()) {
                transactions.add(readTransaction(result));
            }
        }
        return List.copyOf(transactions);
    }

    public boolean transitionTransaction(
            UUID id,
            OpenTransactionStatus expected,
            OpenTransactionStatus next,
            Long completedAt,
            String failureReason
    ) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE open_transactions
                SET status = ?, completed_at = ?, failure_reason = ?
                WHERE id = ? AND status = ?
                """)) {
            statement.setString(1, next.name());
            setNullableLong(statement, 2, completedAt);
            setNullableString(statement, 3, failureReason);
            statement.setString(4, id.toString());
            statement.setString(5, expected.name());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean failTransaction(UUID id, long completedAt, String reason) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE open_transactions
                SET status = 'FAILED', completed_at = ?, failure_reason = ?
                WHERE id = ? AND status <> 'COMPLETED'
                """)) {
            statement.setLong(1, completedAt);
            statement.setString(2, Objects.requireNonNullElse(reason, "unknown failure"));
            statement.setString(3, id.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public void addOpenResult(OpenResult openResult) throws SQLException {
        checkThread();
        Objects.requireNonNull(openResult, "openResult");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO open_results(transaction_id, result_index, reward_id, rarity, delivered)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(transaction_id, result_index) DO UPDATE SET
                    reward_id = excluded.reward_id,
                    rarity = excluded.rarity,
                    delivered = excluded.delivered
                """)) {
            statement.setString(1, openResult.transactionId().toString());
            statement.setInt(2, openResult.resultIndex());
            statement.setString(3, openResult.rewardId());
            statement.setString(4, openResult.rarity().name());
            statement.setInt(5, bool(openResult.delivered()));
            statement.executeUpdate();
        }
    }

    public List<OpenResult> listOpenResults(UUID transactionId) throws SQLException {
        checkThread();
        List<OpenResult> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM open_results WHERE transaction_id = ? ORDER BY result_index
                """)) {
            statement.setString(1, transactionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    results.add(readOpenResult(result));
                }
            }
        }
        return List.copyOf(results);
    }

    public List<OpenResult> listAllOpenResults() throws SQLException {
        checkThread();
        List<OpenResult> results = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM open_results ORDER BY transaction_id, result_index")) {
            while (result.next()) {
                results.add(readOpenResult(result));
            }
        }
        return List.copyOf(results);
    }

    public boolean markOpenResultDelivered(UUID transactionId, int resultIndex) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE open_results SET delivered = 1 WHERE transaction_id = ? AND result_index = ?
                """)) {
            statement.setString(1, transactionId.toString());
            statement.setInt(2, resultIndex);
            return statement.executeUpdate() == 1;
        }
    }

    public long enqueueDelivery(PendingDelivery delivery) throws SQLException {
        checkThread();
        Objects.requireNonNull(delivery, "delivery");
        if (delivery.id() != 0) {
            throw new IllegalArgumentException("new delivery id must be 0");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_deliveries(
                    transaction_id, result_index, player_uuid, reward_id, item_blob, amount,
                    command, status, attempts, last_error, created_at, delivered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            bindDelivery(statement, delivery);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public Optional<PendingDelivery> findDelivery(long id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pending_deliveries WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readDelivery(result)) : Optional.empty();
            }
        }
    }

    public List<PendingDelivery> listPendingDeliveries(UUID playerUuid, int limit) throws SQLException {
        checkThread();
        requirePositiveLimit(limit);
        return listDeliveries("""
                SELECT * FROM pending_deliveries
                WHERE player_uuid = ? AND status = 'PENDING'
                ORDER BY created_at, id LIMIT ?
                """, statement -> {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
        });
    }

    public List<PendingDelivery> listPendingDeliveries(int limit) throws SQLException {
        checkThread();
        requirePositiveLimit(limit);
        return listDeliveries("""
                SELECT * FROM pending_deliveries WHERE status = 'PENDING'
                ORDER BY created_at, id LIMIT ?
                """, statement -> statement.setInt(1, limit));
    }

    public List<PendingDelivery> listUndeliveredDeliveries(UUID playerUuid, int limit) throws SQLException {
        checkThread();
        requirePositiveLimit(limit);
        return listDeliveries("""
                SELECT * FROM pending_deliveries
                WHERE player_uuid = ? AND status <> 'DELIVERED'
                ORDER BY created_at, id LIMIT ?
                """, statement -> {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
        });
    }

    /**
     * Moves transactions which are safe to claim into the sole delivery state. A DRAWN
     * transaction is recoverable only after its persisted scene snapshot has been removed;
     * this prevents a manual claim from bypassing an active scene.
     */
    public int prepareTransactionsForDelivery(UUID playerUuid) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE open_transactions
                SET status = 'DELIVERING', completed_at = NULL
                WHERE player_uuid = ?
                  AND status IN ('DRAWN', 'RECOVERY_REQUIRED')
                  AND NOT EXISTS (
                    SELECT 1 FROM active_scene_recovery recovery
                    WHERE recovery.transaction_id = open_transactions.id
                      AND recovery.recovery_status = 'ACTIVE'
                  )
                """)) {
            statement.setString(1, playerUuid.toString());
            return statement.executeUpdate();
        }
    }

    /** Transaction-scoped form of {@link #prepareTransactionsForDelivery(UUID)}. */
    public int prepareTransactionForDelivery(UUID playerUuid, UUID transactionId) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE open_transactions
                SET status = 'DELIVERING', completed_at = NULL
                WHERE player_uuid = ? AND id = ?
                  AND status IN ('DRAWN', 'RECOVERY_REQUIRED')
                  AND NOT EXISTS (
                    SELECT 1 FROM active_scene_recovery recovery
                    WHERE recovery.transaction_id = open_transactions.id
                      AND recovery.recovery_status = 'ACTIVE'
                  )
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transactionId.toString());
            return statement.executeUpdate();
        }
    }

    /**
     * Returns one keyset-paginated page of automatically claimable rows. Only PENDING
     * rows belonging to a DELIVERING transaction are returned; FAILED rows deliberately
     * remain quarantined until an administrator explicitly calls {@link #resetDeliveryForRetry(long)}.
     * Item rows sort before command rows so a command which kicks or kills a player cannot
     * prevent this claim's item snapshots from being inserted first.
     *
     * @param afterPhase cursor phase: {@code 0} for items, {@code 1} for commands
     * @param afterId id of the last row seen in that phase, or zero for the first page
     */
    public List<PendingDelivery> listClaimableDeliveries(
            UUID playerUuid,
            int afterPhase,
            long afterId,
            int limit
    ) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        requireDeliveryCursor(afterPhase, afterId);
        requirePositiveLimit(limit);
        return listDeliveries("""
                SELECT delivery.*
                FROM pending_deliveries delivery
                JOIN open_transactions transaction_record
                  ON transaction_record.id = delivery.transaction_id
                WHERE delivery.player_uuid = ?
                  AND delivery.status = 'PENDING'
                  AND transaction_record.status = 'DELIVERING'
                  AND (
                    CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END > ?
                    OR (
                      CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END = ?
                      AND delivery.id > ?
                    )
                  )
                ORDER BY CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END, delivery.id
                LIMIT ?
                """, statement -> {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, afterPhase);
            statement.setInt(3, afterPhase);
            statement.setLong(4, afterId);
            statement.setInt(5, limit);
        });
    }

    /** Transaction-scoped form of the keyset-paginated claim query. */
    public List<PendingDelivery> listClaimableDeliveries(
            UUID playerUuid,
            UUID transactionId,
            int afterPhase,
            long afterId,
            int limit
    ) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        requireDeliveryCursor(afterPhase, afterId);
        requirePositiveLimit(limit);
        return listDeliveries("""
                SELECT delivery.*
                FROM pending_deliveries delivery
                JOIN open_transactions transaction_record
                  ON transaction_record.id = delivery.transaction_id
                WHERE delivery.player_uuid = ?
                  AND delivery.transaction_id = ?
                  AND delivery.status = 'PENDING'
                  AND transaction_record.status = 'DELIVERING'
                  AND (
                    CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END > ?
                    OR (
                      CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END = ?
                      AND delivery.id > ?
                    )
                  )
                ORDER BY CASE WHEN delivery.item_blob IS NULL THEN 1 ELSE 0 END, delivery.id
                LIMIT ?
                """, statement -> {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transactionId.toString());
            statement.setInt(3, afterPhase);
            statement.setInt(4, afterPhase);
            statement.setLong(5, afterId);
            statement.setInt(6, limit);
        });
    }

    public int countUndeliveredDeliveries(UUID playerUuid) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM pending_deliveries
                WHERE player_uuid = ? AND status <> 'DELIVERED'
                """)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** Counts distinct unfulfilled draw results for one player and crate without a row limit. */
    public int countUndeliveredResults(UUID playerUuid, String crateId) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        String normalizedCrateId = Objects.requireNonNull(crateId, "crateId").trim();
        if (normalizedCrateId.isEmpty()) {
            throw new IllegalArgumentException("crateId cannot be blank");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM (
                    SELECT delivery.transaction_id, delivery.result_index
                    FROM pending_deliveries delivery
                    JOIN open_transactions transaction_record
                      ON transaction_record.id = delivery.transaction_id
                    WHERE delivery.player_uuid = ?
                      AND transaction_record.crate_id = ?
                      AND delivery.status <> 'DELIVERED'
                    GROUP BY delivery.transaction_id, delivery.result_index
                ) outstanding_results
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, normalizedCrateId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** Counts rows in one persisted delivery status for a player. */
    public int countDeliveries(UUID playerUuid, PendingDeliveryStatus status) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(status, "status");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM pending_deliveries
                WHERE player_uuid = ? AND status = ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, status.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** Transaction-scoped form of {@link #countDeliveries(UUID, PendingDeliveryStatus)}. */
    public int countDeliveries(
            UUID playerUuid,
            UUID transactionId,
            PendingDeliveryStatus status
    ) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(status, "status");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM pending_deliveries
                WHERE player_uuid = ? AND transaction_id = ? AND status = ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transactionId.toString());
            statement.setString(3, status.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public List<PendingDelivery> listAllDeliveries() throws SQLException {
        checkThread();
        return listDeliveries("SELECT * FROM pending_deliveries ORDER BY created_at, id", statement -> {
        });
    }

    public boolean hasUndeliveredDeliveries(UUID transactionId, int resultIndex) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS(
                    SELECT 1 FROM pending_deliveries
                    WHERE transaction_id = ? AND result_index = ? AND status <> 'DELIVERED'
                )
                """)) {
            statement.setString(1, transactionId.toString());
            statement.setInt(2, resultIndex);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) != 0;
            }
        }
    }

    public boolean markDeliveryDelivered(long id, long deliveredAt) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_deliveries
                SET status = 'DELIVERED', attempts = attempts + 1, last_error = NULL, delivered_at = ?
                WHERE id = ? AND status = 'PENDING'
                """)) {
            statement.setLong(1, deliveredAt);
            statement.setLong(2, id);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markDeliveryFailed(long id, String error) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_deliveries
                SET status = 'FAILED', attempts = attempts + 1, last_error = ?, delivered_at = NULL
                WHERE id = ? AND status = 'PENDING'
                """)) {
            statement.setString(1, Objects.requireNonNullElse(error, "unknown delivery failure"));
            statement.setLong(2, id);
            return statement.executeUpdate() == 1;
        }
    }

    /** Persists an item delivery's uninserted remainder so a retry cannot duplicate items already inserted. */
    public boolean updatePendingDeliveryRemaining(
            long id,
            int remainingAmount,
            int attempts,
            String lastError
    ) throws SQLException {
        checkThread();
        if (remainingAmount <= 0 || attempts < 0) {
            throw new IllegalArgumentException("remainingAmount must be positive and attempts non-negative");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_deliveries
                SET amount = ?, status = 'PENDING', attempts = ?, last_error = ?, delivered_at = NULL
                WHERE id = ? AND item_blob IS NOT NULL AND status = 'PENDING'
                """)) {
            statement.setInt(1, remainingAmount);
            statement.setInt(2, attempts);
            setNullableString(statement, 3, lastError);
            statement.setLong(4, id);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markPendingDeliveryDelivered(long id, long deliveredAt) throws SQLException {
        return markDeliveryDelivered(id, deliveredAt);
    }

    public boolean markPendingDeliveryFailed(long id, String error) throws SQLException {
        return markDeliveryFailed(id, error);
    }

    public boolean resetDeliveryForRetry(long id) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE pending_deliveries SET status = 'PENDING', delivered_at = NULL
                WHERE id = ? AND status = 'FAILED'
                """)) {
            statement.setLong(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Reconciles result snapshots and completes only transactions already in DELIVERING.
     * A FAILED/manual-review delivery keeps its result and transaction incomplete.
     * The caller should wrap this method in {@link SQLiteDatabase#transaction(SqlFunction)}.
     *
     * @return number of transactions transitioned to COMPLETED
     */
    public int completeDeliverableTransactions(UUID playerUuid, long completedAt) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        markResultsWithNoOutstandingDeliveries(playerUuid, null);
        return completeReconciledTransactions(playerUuid, null, completedAt);
    }

    /** Transaction-scoped form of {@link #completeDeliverableTransactions(UUID, long)}. */
    public int completeDeliverableTransaction(
            UUID playerUuid,
            UUID transactionId,
            long completedAt
    ) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        markResultsWithNoOutstandingDeliveries(playerUuid, transactionId);
        return completeReconciledTransactions(playerUuid, transactionId, completedAt);
    }

    public boolean deleteDelivery(long id) throws SQLException {
        checkThread();
        return deleteById("DELETE FROM pending_deliveries WHERE id = ?", id);
    }

    public void saveActiveSceneRecovery(ActiveSceneRecovery recovery) throws SQLException {
        checkThread();
        Objects.requireNonNull(recovery, "recovery");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO active_scene_recovery(
                    player_uuid, transaction_id, world_uuid, world, x, y, z, yaw, pitch, game_mode,
                    allow_flight, flying, walk_speed, fly_speed, invulnerable, collidable,
                    camera_entity_uuid, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    transaction_id = excluded.transaction_id,
                    world_uuid = excluded.world_uuid,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch,
                    game_mode = excluded.game_mode,
                    allow_flight = excluded.allow_flight,
                    flying = excluded.flying,
                    walk_speed = excluded.walk_speed,
                    fly_speed = excluded.fly_speed,
                    invulnerable = excluded.invulnerable,
                    collidable = excluded.collidable,
                    camera_entity_uuid = excluded.camera_entity_uuid,
                    created_at = excluded.created_at,
                    recovery_status = CASE
                        WHEN active_scene_recovery.transaction_id = excluded.transaction_id
                            AND active_scene_recovery.recovery_status = 'APPLIED'
                        THEN 'APPLIED'
                        ELSE 'ACTIVE'
                    END,
                    applied_at = CASE
                        WHEN active_scene_recovery.transaction_id = excluded.transaction_id
                            AND active_scene_recovery.recovery_status = 'APPLIED'
                        THEN active_scene_recovery.applied_at
                        ELSE NULL
                    END
                """)) {
            int index = 1;
            statement.setString(index++, recovery.playerUuid().toString());
            statement.setString(index++, recovery.transactionId().toString());
            statement.setString(index++, recovery.worldUuid().toString());
            statement.setString(index++, recovery.world());
            statement.setDouble(index++, recovery.x());
            statement.setDouble(index++, recovery.y());
            statement.setDouble(index++, recovery.z());
            statement.setFloat(index++, recovery.yaw());
            statement.setFloat(index++, recovery.pitch());
            statement.setString(index++, recovery.gameMode());
            statement.setInt(index++, bool(recovery.allowFlight()));
            statement.setInt(index++, bool(recovery.flying()));
            statement.setFloat(index++, recovery.walkSpeed());
            statement.setFloat(index++, recovery.flySpeed());
            statement.setInt(index++, bool(recovery.invulnerable()));
            statement.setInt(index++, bool(recovery.collidable()));
            setNullableString(statement, index++, recovery.cameraEntityUuid() == null
                    ? null : recovery.cameraEntityUuid().toString());
            statement.setLong(index, recovery.createdAt());
            statement.executeUpdate();
        }
    }

    public Optional<ActiveSceneRecovery> findActiveSceneRecovery(UUID playerUuid) throws SQLException {
        checkThread();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM active_scene_recovery WHERE player_uuid = ? AND recovery_status = 'ACTIVE'")) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readRecovery(result)) : Optional.empty();
            }
        }
    }

    public List<ActiveSceneRecovery> listActiveSceneRecoveries() throws SQLException {
        checkThread();
        List<ActiveSceneRecovery> recoveries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM active_scene_recovery WHERE recovery_status = 'ACTIVE' "
                             + "ORDER BY created_at, player_uuid")) {
            while (result.next()) {
                recoveries.add(readRecovery(result));
            }
        }
        return List.copyOf(recoveries);
    }

    /** Durably hides a successfully restored snapshot from all future recovery lookups. */
    public boolean markActiveSceneRecoveryApplied(
            UUID playerUuid,
            UUID transactionId,
            long appliedAt
    ) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        if (appliedAt < 0) {
            throw new IllegalArgumentException("appliedAt must be non-negative");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE active_scene_recovery
                SET recovery_status = 'APPLIED', applied_at = COALESCE(applied_at, ?)
                WHERE player_uuid = ? AND transaction_id = ?
                """)) {
            statement.setLong(1, appliedAt);
            statement.setString(2, playerUuid.toString());
            statement.setString(3, transactionId.toString());
            return statement.executeUpdate() > 0;
        }
    }

    /** Deletes only the expected snapshot, never a newer scene row for the same player. */
    public boolean deleteActiveSceneRecovery(UUID playerUuid, UUID transactionId) throws SQLException {
        checkThread();
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(transactionId, "transactionId");
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM active_scene_recovery WHERE player_uuid = ? AND transaction_id = ?")) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transactionId.toString());
            return statement.executeUpdate() > 0;
        }
    }

    private RewardBundle bundle(RewardDefinition definition) throws SQLException {
        return new RewardBundle(definition, readRewardItems(definition.id()), readRewardCommands(definition.id()));
    }

    private List<RewardItem> readRewardItems(String rewardId) throws SQLException {
        List<RewardItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM reward_items WHERE reward_id = ? ORDER BY id")) {
            statement.setString(1, rewardId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new RewardItem(
                            result.getLong("id"),
                            result.getString("reward_id"),
                            result.getBytes("item_blob"),
                            result.getInt("amount")
                    ));
                }
            }
        }
        return List.copyOf(items);
    }

    private List<RewardCommand> readRewardCommands(String rewardId) throws SQLException {
        List<RewardCommand> commands = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM reward_commands WHERE reward_id = ? ORDER BY execution_order, id
                """)) {
            statement.setString(1, rewardId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    commands.add(new RewardCommand(
                            result.getLong("id"),
                            result.getString("reward_id"),
                            result.getString("command"),
                            result.getInt("execution_order")
                    ));
                }
            }
        }
        return List.copyOf(commands);
    }

    private CrateDefinition readCrate(ResultSet result) throws SQLException {
        return new CrateDefinition(
                result.getString("id"),
                result.getString("display_name"),
                result.getInt("enabled") != 0,
                result.getBytes("key_item_blob"),
                result.getDouble("single_price"),
                result.getDouble("seven_price"),
                result.getInt("guarantee_a"),
                result.getInt("guarantee_s"),
                result.getString("scene_profile_id"),
                nullableRarity(result.getString("broadcast_rarity")),
                result.getInt("skip_allowed") != 0,
                result.getDouble("interaction_width"),
                result.getDouble("interaction_height"),
                result.getString("idle_animation"),
                result.getString("single_open_animation"),
                result.getString("seven_open_animation")
        );
    }

    private RewardDefinition readReward(ResultSet result) throws SQLException {
        return new RewardDefinition(
                result.getString("id"),
                result.getString("crate_id"),
                result.getString("display_name"),
                result.getString("description"),
                Rarity.valueOf(result.getString("rarity")),
                result.getDouble("weight"),
                result.getDouble("display_scale"),
                result.getInt("enabled") != 0,
                result.getInt("broadcast") != 0
        );
    }

    private Placement readPlacement(ResultSet result) throws SQLException {
        return new Placement(
                result.getString("placement_id"),
                result.getString("crate_id"),
                result.getString("world"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch")
        );
    }

    private SceneProfile readSceneProfile(ResultSet result) throws SQLException {
        return new SceneProfile(
                result.getString("id"),
                result.getString("name"),
                result.getString("crate_model"),
                result.getString("loot_model")
        );
    }

    private ScenePoint readScenePoint(ResultSet result) throws SQLException {
        return new ScenePoint(
                result.getString("profile_id"),
                result.getInt("point_index"),
                ScenePointKind.valueOf(result.getString("kind")),
                result.getString("world"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch")
        );
    }

    private PlayerCrateState readPlayerState(ResultSet result) throws SQLException {
        return new PlayerCrateState(
                UUID.fromString(result.getString("player_uuid")),
                result.getString("crate_id"),
                result.getInt("virtual_keys"),
                result.getInt("pity_a"),
                result.getInt("pity_s"),
                result.getLong("total_opens"),
                result.getLong("updated_at")
        );
    }

    private OpenTransaction readTransaction(ResultSet result) throws SQLException {
        return new OpenTransaction(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("crate_id"),
                result.getInt("draw_count"),
                OpenTransactionStatus.valueOf(result.getString("status")),
                result.getLong("created_at"),
                nullableLong(result, "completed_at"),
                result.getString("failure_reason")
        );
    }

    private OpenResult readOpenResult(ResultSet result) throws SQLException {
        return new OpenResult(
                UUID.fromString(result.getString("transaction_id")),
                result.getInt("result_index"),
                result.getString("reward_id"),
                Rarity.valueOf(result.getString("rarity")),
                result.getInt("delivered") != 0
        );
    }

    private PendingDelivery readDelivery(ResultSet result) throws SQLException {
        return new PendingDelivery(
                result.getLong("id"),
                UUID.fromString(result.getString("transaction_id")),
                result.getInt("result_index"),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("reward_id"),
                result.getBytes("item_blob"),
                result.getInt("amount"),
                result.getString("command"),
                PendingDeliveryStatus.valueOf(result.getString("status")),
                result.getInt("attempts"),
                result.getString("last_error"),
                result.getLong("created_at"),
                nullableLong(result, "delivered_at")
        );
    }

    private ActiveSceneRecovery readRecovery(ResultSet result) throws SQLException {
        String cameraUuid = result.getString("camera_entity_uuid");
        return new ActiveSceneRecovery(
                UUID.fromString(result.getString("player_uuid")),
                UUID.fromString(result.getString("transaction_id")),
                UUID.fromString(result.getString("world_uuid")),
                result.getString("world"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getFloat("yaw"),
                result.getFloat("pitch"),
                result.getString("game_mode"),
                result.getInt("allow_flight") != 0,
                result.getInt("flying") != 0,
                result.getFloat("walk_speed"),
                result.getFloat("fly_speed"),
                result.getInt("invulnerable") != 0,
                result.getInt("collidable") != 0,
                cameraUuid == null ? null : UUID.fromString(cameraUuid),
                result.getLong("created_at")
        );
    }

    private void bindDelivery(PreparedStatement statement, PendingDelivery delivery) throws SQLException {
        statement.setString(1, delivery.transactionId().toString());
        statement.setInt(2, delivery.resultIndex());
        statement.setString(3, delivery.playerUuid().toString());
        statement.setString(4, delivery.rewardId());
        setNullableBytes(statement, 5, delivery.itemBlob());
        statement.setInt(6, delivery.amount());
        setNullableString(statement, 7, delivery.command());
        statement.setString(8, delivery.status().name());
        statement.setInt(9, delivery.attempts());
        setNullableString(statement, 10, delivery.lastError());
        statement.setLong(11, delivery.createdAt());
        setNullableLong(statement, 12, delivery.deliveredAt());
    }

    private void markResultsWithNoOutstandingDeliveries(
            UUID playerUuid,
            UUID transactionId
    ) throws SQLException {
        String sql = transactionId == null ? """
                UPDATE open_results
                SET delivered = 1
                WHERE delivered = 0
                  AND EXISTS (
                    SELECT 1 FROM open_transactions transaction_record
                    WHERE transaction_record.id = open_results.transaction_id
                      AND transaction_record.player_uuid = ?
                      AND transaction_record.status = 'DELIVERING'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pending_deliveries delivery
                    WHERE delivery.transaction_id = open_results.transaction_id
                      AND delivery.result_index = open_results.result_index
                      AND delivery.status <> 'DELIVERED'
                  )
                """ : """
                UPDATE open_results
                SET delivered = 1
                WHERE delivered = 0
                  AND transaction_id = ?
                  AND EXISTS (
                    SELECT 1 FROM open_transactions transaction_record
                    WHERE transaction_record.id = open_results.transaction_id
                      AND transaction_record.player_uuid = ?
                      AND transaction_record.status = 'DELIVERING'
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pending_deliveries delivery
                    WHERE delivery.transaction_id = open_results.transaction_id
                      AND delivery.result_index = open_results.result_index
                      AND delivery.status <> 'DELIVERED'
                  )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (transactionId != null) {
                statement.setString(index++, transactionId.toString());
            }
            statement.setString(index, playerUuid.toString());
            statement.executeUpdate();
        }
    }

    private int completeReconciledTransactions(
            UUID playerUuid,
            UUID transactionId,
            long completedAt
    ) throws SQLException {
        if (completedAt < 0) {
            throw new IllegalArgumentException("completedAt must be non-negative");
        }
        String sql = transactionId == null ? """
                UPDATE open_transactions
                SET status = 'COMPLETED', completed_at = ?, failure_reason = NULL
                WHERE player_uuid = ?
                  AND status = 'DELIVERING'
                  AND EXISTS (
                    SELECT 1 FROM open_results result_record
                    WHERE result_record.transaction_id = open_transactions.id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM open_results result_record
                    WHERE result_record.transaction_id = open_transactions.id
                      AND result_record.delivered = 0
                  )
                """ : """
                UPDATE open_transactions
                SET status = 'COMPLETED', completed_at = ?, failure_reason = NULL
                WHERE player_uuid = ?
                  AND id = ?
                  AND status = 'DELIVERING'
                  AND EXISTS (
                    SELECT 1 FROM open_results result_record
                    WHERE result_record.transaction_id = open_transactions.id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM open_results result_record
                    WHERE result_record.transaction_id = open_transactions.id
                      AND result_record.delivered = 0
                  )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, completedAt);
            statement.setString(2, playerUuid.toString());
            if (transactionId != null) {
                statement.setString(3, transactionId.toString());
            }
            return statement.executeUpdate();
        }
    }

    private List<PendingDelivery> listDeliveries(String sql, StatementBinder binder) throws SQLException {
        List<PendingDelivery> deliveries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    deliveries.add(readDelivery(result));
                }
            }
        }
        return List.copyOf(deliveries);
    }

    private long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
        }
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT last_insert_rowid()")) {
            if (result.next()) {
                return result.getLong(1);
            }
        }
        throw new SQLException("SQLite did not return a generated id");
    }

    private boolean deleteById(String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private void checkThread() {
        if (!onDatabaseThread.getAsBoolean()) {
            throw new IllegalStateException("Synchronous DAO calls must run on the RookieCrates SQLite thread");
        }
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableBytes(PreparedStatement statement, int index, byte[] value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BLOB);
        } else {
            statement.setBytes(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Rarity nullableRarity(String value) {
        return value == null ? null : Rarity.valueOf(value);
    }

    private static void requireUpdated(int count, String type, long id) {
        if (count != 1) {
            throw new NoSuchElementException("No " + type + " exists with id " + id);
        }
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void requireDeliveryCursor(int phase, long id) {
        if ((phase != 0 && phase != 1) || id < 0) {
            throw new IllegalArgumentException("delivery cursor must use phase 0/1 and a non-negative id");
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
