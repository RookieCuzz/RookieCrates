package com.cuzz.rookieCrates.gui.api;

import com.cuzz.rookieCrates.config.CrateDefaults;
import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.PlayerCrateState;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardCommand;
import com.cuzz.rookieCrates.domain.RewardDefinition;
import com.cuzz.rookieCrates.domain.RewardItem;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.economy.EconomyGateway;
import com.cuzz.rookieCrates.key.PhysicalKeyService;
import com.cuzz.rookieCrates.runtime.CratePlacement;
import com.cuzz.rookieCrates.runtime.CrateRuntime;
import com.cuzz.rookieCrates.service.ConfigurationTransferService;
import com.cuzz.rookieCrates.service.OpeningCoordinator;
import com.cuzz.rookieCrates.service.PlayerOperationLocks;
import com.cuzz.rookieCrates.service.RewardDeliveryService;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.cuzz.rookieCrates.util.ItemStackCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Concrete async facade backed by SQLite and the Bukkit/ModelEngine runtime. */
public final class DefaultCratesGuiFacade implements CratesGuiFacade {

    private final JavaPlugin plugin;
    private final SQLiteDatabase database;
    private final EconomyGateway economy;
    private final PhysicalKeyService physicalKeys;
    private final ItemStackCodec itemCodec;
    private final OpeningCoordinator openings;
    private final RewardDeliveryService deliveries;
    private final PlayerOperationLocks playerLocks;
    private final CrateRuntime runtime;
    private final ConfigurationTransferService transfers;
    private final CrateDefaults defaults;

    public DefaultCratesGuiFacade(
            JavaPlugin plugin,
            SQLiteDatabase database,
            EconomyGateway economy,
            PhysicalKeyService physicalKeys,
            ItemStackCodec itemCodec,
            OpeningCoordinator openings,
            RewardDeliveryService deliveries,
            PlayerOperationLocks playerLocks,
            CrateRuntime runtime,
            ConfigurationTransferService transfers,
            CrateDefaults defaults
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.physicalKeys = Objects.requireNonNull(physicalKeys, "physicalKeys");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.openings = Objects.requireNonNull(openings, "openings");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.playerLocks = Objects.requireNonNull(playerLocks, "playerLocks");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    public void preferPlacement(UUID playerId, String placementId) {
        openings.preferPlacement(playerId, placementId);
    }

    @Override
    public CompletableFuture<List<CrateView>> listCrates(UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        Player online = Bukkit.getPlayer(viewer);
        boolean admin = online != null && online.hasPermission("rookiecrates.admin");
        return database.submit(dao -> {
            List<ViewSnapshot> snapshots = new ArrayList<>();
            for (CrateDefinition crate : dao.listCrates()) {
                if (!admin && !crate.enabled()) {
                    continue;
                }
                PlayerCrateState state = dao.findPlayerState(viewer, crate.id())
                        .orElse(new PlayerCrateState(viewer, crate.id(), 0, 0, 0, 0, 0));
                SceneProfile profile = crate.sceneProfileId() == null
                        ? null
                        : dao.findSceneProfile(crate.sceneProfileId()).orElse(null);
                snapshots.add(new ViewSnapshot(
                        crate,
                        profile,
                        admin ? dao.listRewards(crate.id()) : dao.listEnabledRewards(crate.id()),
                        state,
                        dao.countUndeliveredResults(viewer, crate.id())
                ));
            }
            return List.copyOf(snapshots);
        }).thenCompose(snapshots -> onMainThread(() -> snapshots.stream()
                .map(snapshot -> toView(snapshot, viewer))
                .toList()));
    }

    @Override
    public CompletableFuture<Optional<CrateView>> getCrate(String crateId, UUID viewer) {
        String id = requireId(crateId);
        Objects.requireNonNull(viewer, "viewer");
        Player online = Bukkit.getPlayer(viewer);
        boolean admin = online != null && online.hasPermission("rookiecrates.admin");
        return database.submit(dao -> {
            Optional<CrateDefinition> found = dao.findCrate(id);
            if (found.isEmpty() || (!admin && !found.get().enabled())) {
                return Optional.<ViewSnapshot>empty();
            }
            CrateDefinition crate = found.get();
            PlayerCrateState state = dao.findPlayerState(viewer, id)
                    .orElse(new PlayerCrateState(viewer, id, 0, 0, 0, 0, 0));
            SceneProfile profile = crate.sceneProfileId() == null
                    ? null
                    : dao.findSceneProfile(crate.sceneProfileId()).orElse(null);
            return Optional.of(new ViewSnapshot(
                    crate,
                    profile,
                    admin ? dao.listRewards(id) : dao.listEnabledRewards(id),
                    state,
                    dao.countUndeliveredResults(viewer, id)
            ));
        }).thenCompose(snapshot -> onMainThread(() -> snapshot.map(value -> toView(value, viewer))));
    }

    @Override
    public CompletableFuture<GuiResult> requestDraw(Player player, String crateId, DrawType drawType) {
        return openings.requestDraw(player, crateId, drawType);
    }

    @Override
    public CompletableFuture<GuiResult> claimPending(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        Optional<CompletableFuture<RewardDeliveryService.DeliveryReport>> operation;
        try {
            operation = playerLocks.tryRunLocked(playerId, () -> deliveries.deliver(player));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not start pending claim for " + playerId + ": "
                    + describe(exception));
            return CompletableFuture.completedFuture(GuiResult.failure("领取服务当前不可用，请稍后重试。" ));
        }
        if (operation.isEmpty()) {
            return CompletableFuture.completedFuture(GuiResult.failure("你已有一个开箱或领取操作正在进行。"));
        }
        return operation.get().handle((report, failure) -> {
            if (failure != null) {
                plugin.getLogger().severe("Pending claim failed for " + playerId + ": " + describe(failure));
                return GuiResult.failure("领取失败，请稍后重试。" );
            }
            if (report.playerUnavailable()) {
                return GuiResult.failure("玩家当前离线或死亡，未处理的奖励仍保留在待领取列表。" );
            }
            if (report.deliveredRows() == 0 && report.remainingRows() == 0 && report.failedRows() == 0) {
                return GuiResult.success("当前没有待领取奖励。" );
            }
            if (report.failedRows() > 0) {
                return GuiResult.success("已发放可安全处理的奖励；仍有 " + report.failedRows()
                        + " 项命令结果不确定，已停止自动重试，请联系管理员核对。" );
            }
            if (report.remainingRows() > 0) {
                return GuiResult.success("已领取可放入的奖励；背包仍放不下的部分继续保留。" );
            }
            return GuiResult.success("待领取奖励已全部发放。" );
        });
    }

    @Override
    public CompletableFuture<GuiResult> createCrate(String crateId, String displayName) {
        String id = requireId(crateId);
        String name = requireText(displayName, "displayName");
        String profileId = id + "_scene";
        Rarity broadcastRarity = defaultBroadcastRarity();
        return result(database.transaction(dao -> {
            if (dao.findCrate(id).isPresent()) {
                throw new IllegalArgumentException("宝箱 ID 已存在。" );
            }
            dao.upsertSceneProfile(new SceneProfile(
                    profileId,
                    name + " 场景",
                    defaults.crateModel(),
                    defaults.lootModels().modelFor(Rarity.C)
            ));
            dao.upsertCrate(new CrateDefinition(
                    id,
                    name,
                    false,
                    null,
                    defaults.singlePrice(),
                    defaults.sevenPrice(),
                    defaults.guaranteeA(),
                    defaults.guaranteeS(),
                    profileId,
                    broadcastRarity,
                    defaults.skipAllowed(),
                    defaults.interactionWidth(),
                    defaults.interactionHeight(),
                    defaults.idleAnimation(),
                    defaults.openAnimation()
            ));
            return null;
        }), ignored -> "宝箱已创建；请设置钥匙、奖励、场景和模型入口后再启用。" );
    }

    @Override
    public CompletableFuture<GuiResult> deleteCrate(String crateId) {
        String id = requireId(crateId);
        return result(database.transaction(dao -> {
            CrateDefinition crate = dao.findCrate(id)
                    .orElseThrow(() -> new IllegalArgumentException("宝箱不存在。" ));
            List<Placement> placements = dao.listPlacements(id);
            dao.deleteCrate(id);
            if (crate.sceneProfileId() != null) {
                dao.deleteSceneProfile(crate.sceneProfileId());
            }
            return placements.size();
        }).thenCompose(ignored -> reloadRuntime()), ignored -> "宝箱及其奖池、入口和场景已删除。" );
    }

    @Override
    public CompletableFuture<GuiResult> saveCrate(CrateSettings settings) {
        Objects.requireNonNull(settings, "settings");
        byte[] keyBlob = settings.keyTemplate() == null ? null : itemCodec.encode(settings.keyTemplate());
        return result(database.transaction(dao -> {
            CrateDefinition old = dao.findCrate(settings.id())
                    .orElseThrow(() -> new IllegalArgumentException("宝箱不存在。" ));
            String profileId = old.sceneProfileId() == null ? settings.id() + "_scene" : old.sceneProfileId();
            dao.upsertSceneProfile(new SceneProfile(
                    profileId,
                    settings.displayName() + " 场景",
                    settings.crateModel(),
                    settings.lootModel()
            ));
            dao.upsertCrate(new CrateDefinition(
                    settings.id(),
                    settings.displayName(),
                    settings.enabled(),
                    keyBlob,
                    settings.singlePrice(),
                    settings.sevenPrice(),
                    settings.pityA(),
                    settings.pityS(),
                    profileId,
                    settings.broadcastS() ? Rarity.S : null,
                    settings.skipAllowed(),
                    settings.interactionWidth(),
                    settings.interactionHeight(),
                    settings.idleAnimation(),
                    settings.openAnimation()
            ));
            return null;
        }).thenCompose(ignored -> reloadRuntime()), ignored -> "宝箱设置已保存。" );
    }

    @Override
    public CompletableFuture<GuiResult> saveReward(String crateId, RewardSettings settings) {
        String id = requireId(crateId);
        Objects.requireNonNull(settings, "settings");
        List<RewardItem> items = new ArrayList<>();
        if (settings.itemReward() != null) {
            ItemStack captured = settings.itemReward();
            int amount = captured.getAmount();
            captured.setAmount(1);
            items.add(new RewardItem(0, settings.id(), itemCodec.encode(captured), amount));
        }
        List<RewardCommand> commands = new ArrayList<>();
        for (int index = 0; index < settings.consoleCommands().size(); index++) {
            commands.add(new RewardCommand(0, settings.id(), settings.consoleCommands().get(index), index));
        }
        RewardBundle bundle = new RewardBundle(
                new RewardDefinition(
                        settings.id(),
                        id,
                        settings.displayName(),
                        "",
                        settings.rarity(),
                        settings.weight(),
                        true,
                        settings.broadcast()
                ),
                items,
                commands
        );
        return result(database.transaction(dao -> {
            if (dao.findCrate(id).isEmpty()) {
                throw new IllegalArgumentException("宝箱不存在。" );
            }
            dao.findReward(settings.id()).ifPresent(existing -> {
                if (!existing.definition().crateId().equals(id)) {
                    throw new IllegalArgumentException("奖励 ID 已被另一个宝箱使用。" );
                }
            });
            dao.replaceRewardBundle(bundle);
            return null;
        }), ignored -> "奖励已保存。" );
    }

    @Override
    public CompletableFuture<GuiResult> deleteReward(String crateId, String rewardId) {
        String id = requireId(crateId);
        String reward = requireId(rewardId);
        return result(database.transaction(dao -> {
            RewardBundle existing = dao.findReward(reward)
                    .orElseThrow(() -> new IllegalArgumentException("奖励不存在。" ));
            if (!existing.definition().crateId().equals(id)) {
                throw new IllegalArgumentException("奖励不属于该宝箱。" );
            }
            dao.deleteReward(reward);
            return null;
        }), ignored -> "奖励已删除。" );
    }

    @Override
    public CompletableFuture<GuiResult> givePhysicalKeys(Player target, String crateId, int amount) {
        Objects.requireNonNull(target, "target");
        String id = requireId(crateId);
        requirePositive(amount, "amount");
        return database.submit(dao -> dao.findCrate(id)
                        .orElseThrow(() -> new IllegalArgumentException("宝箱不存在。" )))
                .thenCompose(crate -> onMainThread(() -> {
                    if (crate.keyItemBlob() == null) {
                        throw new IllegalArgumentException("该宝箱尚未设置钥匙模板。" );
                    }
                    ItemStack template = itemCodec.decode(crate.keyItemBlob());
                    Map<Integer, ItemStack> overflow = physicalKeys.give(
                            target.getInventory(), template, id, amount);
                    overflow.values().forEach(stack -> target.getWorld()
                            .dropItemNaturally(target.getLocation(), stack));
                    return overflow.isEmpty()
                            ? "已发放 " + amount + " 把实体钥匙。"
                            : "已发放钥匙；背包放不下的部分掉落在玩家脚下。";
                }))
                .handle((message, failure) -> failure == null
                        ? GuiResult.success(message)
                        : GuiResult.failure(describe(failure)));
    }

    @Override
    public CompletableFuture<GuiResult> addVirtualKeys(UUID target, String crateId, int amount) {
        Objects.requireNonNull(target, "target");
        String id = requireId(crateId);
        requirePositive(amount, "amount");
        return result(database.transaction(dao -> {
            if (dao.findCrate(id).isEmpty()) {
                throw new IllegalArgumentException("宝箱不存在。" );
            }
            dao.addVirtualKeys(target, id, amount, System.currentTimeMillis());
            return null;
        }), ignored -> "已增加 " + amount + " 把虚拟钥匙。" );
    }

    @Override
    public CompletableFuture<GuiResult> placeModelCrate(String crateId, Location location) {
        String id = requireId(crateId);
        StoredLocation stored = StoredLocation.capture(location);
        return result(database.transaction(dao -> {
            if (dao.findCrate(id).isEmpty()) {
                throw new IllegalArgumentException("宝箱不存在。" );
            }
            String placementId = dao.listPlacements(id).stream()
                    .map(Placement::placementId)
                    .min(String::compareTo)
                    .orElse(id + "_main");
            dao.upsertPlacement(stored.toPlacement(placementId, id));
            return null;
        }).thenCompose(ignored -> reloadRuntime()), ignored ->
                "模型入口已保存；若场景点尚不完整，补齐 crate、camera、loot1..loot7 后会显示。" );
    }

    @Override
    public CompletableFuture<GuiResult> removePlacement(String crateId) {
        String id = requireId(crateId);
        return result(database.transaction(dao -> {
            List<Placement> placements = dao.listPlacements(id);
            for (Placement placement : placements) {
                dao.deletePlacement(placement.placementId());
            }
            return placements.size();
        }).thenCompose(count -> reloadRuntime().thenApply(ignored -> count)), count -> count == 0
                ? "该宝箱没有模型入口。"
                : "已移除 " + count + " 个模型入口。" );
    }

    @Override
    public CompletableFuture<GuiResult> setScenePoint(
            String crateId,
            CratesGuiFacade.ScenePoint point,
            Location location
    ) {
        String id = requireId(crateId);
        Objects.requireNonNull(point, "point");
        StoredLocation stored = StoredLocation.capture(location);
        PointKey key = PointKey.from(point);
        return result(database.transaction(dao -> {
            CrateDefinition crate = dao.findCrate(id)
                    .orElseThrow(() -> new IllegalArgumentException("宝箱不存在。" ));
            if (crate.sceneProfileId() == null) {
                throw new IllegalArgumentException("宝箱没有场景配置。" );
            }
            dao.upsertScenePoint(stored.toScenePoint(crate.sceneProfileId(), key.kind(), key.index()));
            return null;
        }).thenCompose(ignored -> reloadRuntime()), ignored -> point.name() + " 场景点已保存。" );
    }

    @Override
    public CompletableFuture<GuiResult> exportConfig() {
        return transfers.exportAll().handle((path, failure) -> failure == null
                ? GuiResult.success("已导出到 " + path)
                : GuiResult.failure("导出失败：" + describe(failure)));
    }

    @Override
    public CompletableFuture<GuiResult> importConfig() {
        if (!playerLocks.tryBeginMaintenance()) {
            return CompletableFuture.completedFuture(GuiResult.failure(
                    "当前有玩家正在开箱或领取，无法导入；请等待操作结束后重试。"));
        }
        final CompletableFuture<GuiResult> operation;
        try {
            operation = transfers.importAll()
                    .thenCompose(report -> reloadRuntime().thenApply(ignored -> report))
                    .handle((report, failure) -> failure == null
                            ? GuiResult.success("导入完成：" + report.crates() + " 个宝箱、"
                            + report.rewards() + " 个奖励、" + report.playerStates() + " 条玩家数据。")
                            : GuiResult.failure("导入失败：" + describe(failure)));
        } catch (RuntimeException | Error exception) {
            playerLocks.endMaintenance();
            if (exception instanceof Error error) {
                throw error;
            }
            return CompletableFuture.completedFuture(GuiResult.failure("导入失败：" + describe(exception)));
        }
        return operation.whenComplete((ignored, failure) -> playerLocks.endMaintenance());
    }

    /** Reloads all complete model placements without ever touching Bukkit objects on the DB thread. */
    public CompletableFuture<Void> reloadRuntime() {
        return database.submit(dao -> new RuntimeSnapshot(
                dao.listCrates(),
                dao.listSceneProfiles(),
                dao.listAllScenePoints(),
                dao.listAllPlacements()
        )).thenCompose(snapshot -> onMainThread(() -> {
            runtime.reload(toRuntimePlacements(snapshot));
            return null;
        }));
    }

    private List<CratePlacement> toRuntimePlacements(RuntimeSnapshot snapshot) {
        Map<String, CrateDefinition> crates = new HashMap<>();
        snapshot.crates().forEach(crate -> crates.put(crate.id(), crate));
        Map<String, SceneProfile> profiles = new HashMap<>();
        snapshot.profiles().forEach(profile -> profiles.put(profile.id(), profile));
        Map<String, List<com.cuzz.rookieCrates.domain.ScenePoint>> points = new HashMap<>();
        snapshot.points().forEach(point -> points.computeIfAbsent(point.profileId(), ignored -> new ArrayList<>())
                .add(point));

        List<CratePlacement> resolved = new ArrayList<>();
        for (Placement placement : snapshot.placements()) {
            CrateDefinition crate = crates.get(placement.crateId());
            SceneProfile profile = crate == null || crate.sceneProfileId() == null
                    ? null
                    : profiles.get(crate.sceneProfileId());
            if (crate == null || profile == null) {
                continue;
            }
            try {
                resolved.add(resolveRuntimePlacement(
                        crate,
                        profile,
                        points.getOrDefault(profile.id(), List.of()),
                        placement
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Placement '" + placement.placementId()
                        + "' is waiting for valid scene points: " + exception.getMessage());
            }
        }
        return List.copyOf(resolved);
    }

    private CratePlacement resolveRuntimePlacement(
            CrateDefinition crate,
            SceneProfile profile,
            List<com.cuzz.rookieCrates.domain.ScenePoint> scenePoints,
            Placement placement
    ) {
        Map<ScenePointKind, Map<Integer, com.cuzz.rookieCrates.domain.ScenePoint>> indexed =
                new EnumMap<>(ScenePointKind.class);
        for (com.cuzz.rookieCrates.domain.ScenePoint point : scenePoints) {
            indexed.computeIfAbsent(point.kind(), ignored -> new HashMap<>()).put(point.pointIndex(), point);
        }
        com.cuzz.rookieCrates.domain.ScenePoint cratePoint = requirePoint(indexed, ScenePointKind.CRATE, 1);
        com.cuzz.rookieCrates.domain.ScenePoint cameraPoint = requirePoint(indexed, ScenePointKind.CAMERA, 1);
        List<Location> loot = new ArrayList<>(7);
        for (int index = 1; index <= 7; index++) {
            loot.add(location(requirePoint(indexed, ScenePointKind.LOOT, index)));
        }
        return new CratePlacement(
                crate.id(),
                placement.placementId(),
                location(placement),
                location(cratePoint),
                location(cameraPoint),
                loot,
                (float) crate.interactionWidth(),
                (float) crate.interactionHeight(),
                profile.crateModel(),
                crate.idleAnimation()
        );
    }

    private com.cuzz.rookieCrates.domain.ScenePoint requirePoint(
            Map<ScenePointKind, Map<Integer, com.cuzz.rookieCrates.domain.ScenePoint>> points,
            ScenePointKind kind,
            int index
    ) {
        com.cuzz.rookieCrates.domain.ScenePoint point = points.getOrDefault(kind, Map.of()).get(index);
        if (point == null) {
            throw new IllegalArgumentException("missing " + kind + " " + index);
        }
        return point;
    }

    private CrateView toView(ViewSnapshot snapshot, UUID viewer) {
        CrateDefinition crate = snapshot.crate();
        Player player = Bukkit.getPlayer(viewer);
        ItemStack keyTemplate = decodeOrNull(crate.keyItemBlob());
        SceneProfile profile = snapshot.profile();
        CrateSettings settings = new CrateSettings(
                crate.id(),
                crate.displayName(),
                crate.enabled(),
                new ItemStack(Material.CHEST),
                keyTemplate,
                crate.singlePrice(),
                crate.sevenPrice(),
                crate.guaranteeA(),
                crate.guaranteeS(),
                crate.broadcastRarity() == Rarity.S,
                crate.skipAllowed(),
                crate.interactionWidth(),
                crate.interactionHeight(),
                profile == null ? defaults.crateModel() : profile.crateModel(),
                profile == null ? defaults.lootModels().modelFor(Rarity.C) : profile.lootModel(),
                crate.idleAnimation(),
                crate.openAnimation()
        );
        double totalWeight = snapshot.rewards().stream()
                .mapToDouble(reward -> reward.definition().weight())
                .sum();
        List<RewardView> rewards = snapshot.rewards().stream()
                .map(reward -> new RewardView(
                        toRewardSettings(reward),
                        totalWeight <= 0.0D ? 0.0D : reward.definition().weight() / totalWeight
                ))
                .toList();
        PlayerCrateState state = snapshot.state();
        PlayerStats stats = new PlayerStats(
                state.virtualKeys(),
                state.totalOpens(),
                state.pityA(),
                state.pityS(),
                snapshot.pendingResults()
        );
        int physical = player == null ? 0 : physicalKeys.count(player.getInventory(), crate.id());
        double balance = player == null ? 0.0D : economy.balance(player);
        return new CrateView(settings, rewards, stats, economy.isAvailable(), balance, physical);
    }

    private RewardSettings toRewardSettings(RewardBundle reward) {
        ItemStack item = null;
        if (!reward.items().isEmpty()) {
            RewardItem stored = reward.items().getFirst();
            item = decodeOrNull(stored.itemBlob());
            if (item != null) {
                item.setAmount(Math.min(stored.amount(), item.getMaxStackSize()));
            }
        }
        List<String> commands = reward.commands().stream()
                .sorted(Comparator.comparingInt(RewardCommand::executionOrder))
                .map(RewardCommand::command)
                .toList();
        return new RewardSettings(
                reward.definition().id(),
                reward.definition().displayName(),
                item,
                item,
                commands,
                reward.definition().weight(),
                reward.definition().rarity(),
                reward.definition().broadcast()
        );
    }

    private ItemStack decodeOrNull(byte[] blob) {
        if (blob == null) {
            return null;
        }
        try {
            return itemCodec.decode(blob);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not decode a stored ItemStack: " + exception.getMessage());
            return null;
        }
    }

    private Location location(Placement placement) {
        World world = Bukkit.getWorld(placement.world());
        if (world == null) {
            throw new IllegalArgumentException("world not loaded: " + placement.world());
        }
        return new Location(world, placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
    }

    private Location location(com.cuzz.rookieCrates.domain.ScenePoint point) {
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            throw new IllegalArgumentException("world not loaded: " + point.world());
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private <T> CompletableFuture<GuiResult> result(
            CompletableFuture<T> future,
            Function<T, String> successMessage
    ) {
        return future.handle((value, failure) -> {
            if (failure == null) {
                return GuiResult.success(successMessage.apply(value));
            }
            Throwable cause = unwrap(failure);
            plugin.getLogger().warning("GUI operation failed: " + describe(cause));
            return GuiResult.failure(describe(cause));
        });
    }

    private <T> CompletableFuture<T> onMainThread(Supplier<T> supplier) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    private static String requireId(String value) {
        String id = requireText(value, "id").toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("ID 只能包含小写字母、数字、_ 和 -。" );
        }
        return id;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空。" );
        }
        return normalized;
    }

    private static void requirePositive(int amount, String name) {
        if (amount <= 0) {
            throw new IllegalArgumentException(name + " 必须是正整数。" );
        }
    }

    private Rarity defaultBroadcastRarity() {
        String configured = plugin.getConfig().getString("broadcast.minimum-rarity", "S");
        try {
            return Rarity.valueOf(configured == null
                    ? "S"
                    : configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid broadcast.minimum-rarity '" + configured + "'; using S");
            return Rarity.S;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private record ViewSnapshot(
            CrateDefinition crate,
            SceneProfile profile,
            List<RewardBundle> rewards,
            PlayerCrateState state,
            int pendingResults
    ) { }

    private record RuntimeSnapshot(
            List<CrateDefinition> crates,
            List<SceneProfile> profiles,
            List<com.cuzz.rookieCrates.domain.ScenePoint> points,
            List<Placement> placements
    ) { }

    private record PointKey(ScenePointKind kind, int index) {
        private static PointKey from(CratesGuiFacade.ScenePoint point) {
            return switch (point) {
                case CRATE -> new PointKey(ScenePointKind.CRATE, 1);
                case CAMERA -> new PointKey(ScenePointKind.CAMERA, 1);
                case LOOT_1 -> new PointKey(ScenePointKind.LOOT, 1);
                case LOOT_2 -> new PointKey(ScenePointKind.LOOT, 2);
                case LOOT_3 -> new PointKey(ScenePointKind.LOOT, 3);
                case LOOT_4 -> new PointKey(ScenePointKind.LOOT, 4);
                case LOOT_5 -> new PointKey(ScenePointKind.LOOT, 5);
                case LOOT_6 -> new PointKey(ScenePointKind.LOOT, 6);
                case LOOT_7 -> new PointKey(ScenePointKind.LOOT, 7);
            };
        }
    }

    private record StoredLocation(
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        private static StoredLocation capture(Location location) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(location.getWorld(), "location.world");
            return new StoredLocation(
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
        }

        private Placement toPlacement(String placementId, String crateId) {
            return new Placement(placementId, crateId, world, x, y, z, yaw, pitch);
        }

        private com.cuzz.rookieCrates.domain.ScenePoint toScenePoint(
                String profileId,
                ScenePointKind kind,
                int index
        ) {
            return new com.cuzz.rookieCrates.domain.ScenePoint(
                    profileId, index, kind, world, x, y, z, yaw, pitch);
        }
    }
}
