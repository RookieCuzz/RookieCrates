package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.config.LootModelPalette;
import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.runtime.model.RuntimeModelHandle;
import com.cuzz.rookieCrates.service.PitySelector;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.cuzz.rookieCrates.util.ItemStackCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGenerator;
import java.util.function.Supplier;

/** Spawns a temporary, private seven-result preview at a crate's LOOT_1..LOOT_7 points. */
public final class LootPreviewController implements Listener {

    public static final int PREVIEW_COUNT = 7;

    private final Plugin plugin;
    private final SQLiteDatabase database;
    private final ItemStackCodec itemCodec;
    private final LootModelPalette lootModels;
    private final RandomGenerator random;
    private final long durationTicks;
    private final Map<UUID, PreviewSession> sessions = new HashMap<>();

    public LootPreviewController(
            Plugin plugin,
            SQLiteDatabase database,
            ItemStackCodec itemCodec,
            LootModelPalette lootModels,
            RandomGenerator random,
            long durationTicks
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.lootModels = Objects.requireNonNull(lootModels, "lootModels");
        this.random = Objects.requireNonNull(random, "random");
        if (durationTicks <= 0L) {
            throw new IllegalArgumentException("preview duration must be positive");
        }
        this.durationTicks = durationTicks;
    }

    public CompletableFuture<PreviewReport> preview(Player player, String crateId) {
        Objects.requireNonNull(player, "player");
        String id = requireText(crateId, "crateId");
        return database.submit(dao -> {
            CrateDefinition crate = dao.findCrate(id)
                    .orElseThrow(() -> new IllegalArgumentException("宝箱不存在。"));
            if (crate.sceneProfileId() == null) {
                throw new IllegalArgumentException("宝箱没有场景配置。");
            }
            List<ScenePoint> lootPoints = dao.listScenePoints(crate.sceneProfileId()).stream()
                    .filter(point -> point.kind() == ScenePointKind.LOOT)
                    .sorted(Comparator.comparingInt(ScenePoint::pointIndex))
                    .toList();
            validateLootPoints(lootPoints);

            List<RewardBundle> rewards = dao.listEnabledRewards(id);
            if (rewards.isEmpty()) {
                throw new IllegalArgumentException("宝箱没有已启用的奖励可供预览。");
            }
            List<RewardBundle> selected = selectPreviewRewards(rewards, random);
            return new PreviewSnapshot(lootPoints, selected);
        }).thenCompose(snapshot -> onMainThread(() -> spawn(player, snapshot)));
    }

    static List<RewardBundle> selectPreviewRewards(List<RewardBundle> rewards, RandomGenerator random) {
        List<PitySelector.Candidate<RewardBundle>> candidates = rewards.stream()
                .map(reward -> new PitySelector.Candidate<>(
                        reward,
                        reward.definition().rarity(),
                        reward.definition().weight()
                ))
                .toList();
        return new PitySelector<RewardBundle>(random).drawMany(
                        candidates,
                        new PitySelector.Progress(0, 0),
                        new PitySelector.Policy(Integer.MAX_VALUE, Integer.MAX_VALUE),
                        PREVIEW_COUNT
                ).stream()
                .map(draw -> draw.candidate().value())
                .toList();
    }

    public void clear(UUID playerId) {
        requireMainThread();
        PreviewSession session = sessions.remove(Objects.requireNonNull(playerId, "playerId"));
        if (session != null) {
            session.close();
        }
    }

    public void shutdown() {
        requireMainThread();
        sessions.values().forEach(PreviewSession::close);
        sessions.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    private PreviewReport spawn(Player player, PreviewSnapshot snapshot) {
        requireMainThread();
        if (!player.isOnline() || player.isDead()) {
            throw new IllegalStateException("玩家当前无法查看预览。");
        }
        clear(player.getUniqueId());

        List<RuntimeModelHandle> spawned = new ArrayList<>(PREVIEW_COUNT);
        BukkitTask cleanupTask = null;
        try {
            for (int index = 0; index < PREVIEW_COUNT; index++) {
                RewardBundle reward = snapshot.rewards().get(index);
                ScenePoint point = snapshot.lootPoints().get(index);
                RuntimeModelHandle model = RuntimeModelHandle.spawnPrivate(
                        location(point),
                        lootModels.modelFor(reward.definition().rarity()),
                        player
                );
                spawned.add(model);
                model.configureLoot(displayItem(reward), reward.definition().displayName());
                if (!model.playAnimation("idle", true)) {
                    throw new IllegalArgumentException("Loot 模型缺少 idle 动画："
                            + lootModels.modelFor(reward.definition().rarity()));
                }
            }
            cleanupTask = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> clear(player.getUniqueId()),
                    durationTicks
            );
            sessions.put(player.getUniqueId(), new PreviewSession(spawned, cleanupTask));
            return new PreviewReport(PREVIEW_COUNT, durationTicks);
        } catch (RuntimeException exception) {
            if (cleanupTask != null) {
                cleanupTask.cancel();
            }
            spawned.forEach(this::safelyRemove);
            throw exception;
        }
    }

    private ItemStack displayItem(RewardBundle reward) {
        if (reward.items().isEmpty()) {
            return new ItemStack(Material.COMMAND_BLOCK);
        }
        ItemStack item = itemCodec.decode(reward.items().getFirst().itemBlob());
        item.setAmount(1);
        return item;
    }

    private Location location(ScenePoint point) {
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            throw new IllegalArgumentException("世界未加载：" + point.world());
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    private static void validateLootPoints(List<ScenePoint> points) {
        if (points.size() != PREVIEW_COUNT) {
            throw new IllegalArgumentException("需要完整配置 LOOT_1..LOOT_7 后才能预览。");
        }
        for (int index = 1; index <= PREVIEW_COUNT; index++) {
            if (points.get(index - 1).pointIndex() != index) {
                throw new IllegalArgumentException("缺少 LOOT_" + index + " 点位。");
            }
        }
    }

    private void safelyRemove(RuntimeModelHandle model) {
        try {
            model.remove();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("无法清理 Loot 预览模型：" + exception.getMessage());
        }
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

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Loot preview operations must run on the Bukkit main thread");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public record PreviewReport(int models, long durationTicks) { }

    private record PreviewSnapshot(List<ScenePoint> lootPoints, List<RewardBundle> rewards) { }

    private final class PreviewSession {
        private final List<RuntimeModelHandle> models;
        private final BukkitTask cleanupTask;
        private boolean closed;

        private PreviewSession(List<RuntimeModelHandle> models, BukkitTask cleanupTask) {
            this.models = List.copyOf(models);
            this.cleanupTask = cleanupTask;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                cleanupTask.cancel();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("无法取消 Loot 预览清理任务：" + exception.getMessage());
            } finally {
                models.forEach(LootPreviewController.this::safelyRemove);
            }
        }
    }
}
