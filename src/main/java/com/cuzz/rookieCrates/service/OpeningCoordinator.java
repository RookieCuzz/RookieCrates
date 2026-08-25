package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.config.LootModelPalette;
import com.cuzz.rookieCrates.domain.CrateDefinition;
import com.cuzz.rookieCrates.domain.OpenResult;
import com.cuzz.rookieCrates.domain.OpenTransaction;
import com.cuzz.rookieCrates.domain.OpenTransactionStatus;
import com.cuzz.rookieCrates.domain.PendingDelivery;
import com.cuzz.rookieCrates.domain.PendingDeliveryStatus;
import com.cuzz.rookieCrates.domain.Placement;
import com.cuzz.rookieCrates.domain.PlayerCrateState;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.domain.RewardCommand;
import com.cuzz.rookieCrates.domain.RewardItem;
import com.cuzz.rookieCrates.domain.ScenePoint;
import com.cuzz.rookieCrates.domain.ScenePointKind;
import com.cuzz.rookieCrates.domain.SceneProfile;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.DrawType;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.GuiResult;
import com.cuzz.rookieCrates.runtime.CratePlacement;
import com.cuzz.rookieCrates.runtime.SceneAbortReason;
import com.cuzz.rookieCrates.runtime.SceneAbortedException;
import com.cuzz.rookieCrates.runtime.SceneController;
import com.cuzz.rookieCrates.runtime.SceneRequest;
import com.cuzz.rookieCrates.runtime.SceneReward;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.cuzz.rookieCrates.util.ItemStackCodec;
import com.cuzz.rookieCrates.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Executes payment, seven-aware pity selection, durable draw snapshots, scenes and delivery. */
public final class OpeningCoordinator {

    private final JavaPlugin plugin;
    private final SQLiteDatabase database;
    private final PaymentService payments;
    private final RewardDeliveryService deliveries;
    private final ItemStackCodec itemCodec;
    private final SceneController scenes;
    private final Messages messages;
    private final PlayerOperationLocks playerLocks;
    private final PitySelector<RewardBundle> selector;
    private final LootModelPalette lootModels;
    private final Map<UUID, String> preferredPlacements = new ConcurrentHashMap<>();
    private final Set<UUID> finalizingTransactions = ConcurrentHashMap.newKeySet();

    public OpeningCoordinator(
            JavaPlugin plugin,
            SQLiteDatabase database,
            PaymentService payments,
            RewardDeliveryService deliveries,
            ItemStackCodec itemCodec,
            SceneController scenes,
            Messages messages,
            PlayerOperationLocks playerLocks,
            PitySelector<RewardBundle> selector,
            LootModelPalette lootModels
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.playerLocks = Objects.requireNonNull(playerLocks, "playerLocks");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.lootModels = Objects.requireNonNull(lootModels, "lootModels");
    }

    /** Makes a subsequent GUI draw use the exact model entry the player clicked. */
    public void preferPlacement(UUID playerId, String placementId) {
        preferredPlacements.put(Objects.requireNonNull(playerId, "playerId"),
                requireText(placementId, "placementId"));
    }

    public CompletableFuture<GuiResult> requestDraw(Player player, String crateId, DrawType drawType) {
        Objects.requireNonNull(player, "player");
        String normalizedCrateId = requireText(crateId, "crateId");
        Objects.requireNonNull(drawType, "drawType");
        UUID playerId = player.getUniqueId();
        AtomicBoolean handedToFinalizer = new AtomicBoolean(false);
        String preferred = preferredPlacements.get(playerId);
        CompletableFuture<GuiResult> pipeline;
        try {
            var started = playerLocks.tryStartLocked(playerId, () -> database.submit(dao -> {
                        CrateDefinition crate = dao.findCrate(normalizedCrateId)
                                .orElseThrow(() -> reject("宝箱不存在。"));
                        PlayerCrateState state = dao.getOrCreatePlayerState(
                                playerId, normalizedCrateId, System.currentTimeMillis());
                        SceneProfile profile = crate.sceneProfileId() == null
                                ? null
                                : dao.findSceneProfile(crate.sceneProfileId()).orElse(null);
                        return new OpenContext(
                                crate,
                                profile,
                                profile == null ? List.of() : dao.listScenePoints(profile.id()),
                                dao.listPlacements(crate.id()),
                                dao.listEnabledRewards(crate.id()),
                                state,
                                preferred
                        );
                    })
                    .thenCompose(context -> onMainThread(() -> prepareCharge(player, drawType, context)))
                    .thenCompose(this::persistWithRefund)
                    .thenCompose(commit -> onMainThread(() -> {
                        handedToFinalizer.set(true);
                        boolean accepted;
                        try {
                            accepted = startScene(player, commit);
                        } catch (RuntimeException exception) {
                            finishCommittedDraw(player, commit, exception);
                            return GuiResult.success("抽奖已记录；场景初始化失败，奖励将直接发放或进入待领取。" );
                        }
                        if (!accepted) {
                            finishCommittedDraw(player, commit, new IllegalStateException("场景当前无法启动"));
                            return GuiResult.success("抽奖已记录；场景无法启动，奖励将直接发放或进入待领取。" );
                        }
                        return GuiResult.success(drawType == DrawType.SEVEN ? "七连抽已开始。" : "单抽已开始。");
                    })));
            if (started.isEmpty()) {
                return CompletableFuture.completedFuture(GuiResult.failure(
                        "你已有一个开箱、领取或维护操作正在进行。"));
            }
            if (preferred != null) {
                preferredPlacements.remove(playerId, preferred);
            }
            pipeline = started.get();
        } catch (RuntimeException exception) {
            pipeline = CompletableFuture.failedFuture(exception);
        }

        return pipeline.handle((result, failure) -> {
            if (failure == null) {
                return result;
            }
            if (!handedToFinalizer.get()) {
                playerLocks.unlock(playerId);
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof OpeningRejectedException rejected) {
                return GuiResult.failure(rejected.userMessage());
            }
            plugin.getLogger().severe("Opening failed for " + playerId + ": " + describe(cause));
            if (cause.getSuppressed().length > 0) {
                return GuiResult.failure("开箱失败且自动退款未完全成功，请联系管理员核对日志。" );
            }
            return GuiResult.failure("开箱失败，已退回本次实体钥匙和已扣金币。" );
        });
    }

    private ChargedOpen prepareCharge(Player player, DrawType drawType, OpenContext context) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Payment must run on the Bukkit main thread");
        }
        CrateDefinition crate = context.crate();
        if (!player.isOnline() || player.isDead()) {
            throw reject("玩家当前无法开箱。" );
        }
        if (!crate.enabled()) {
            throw reject("该宝箱当前未启用。" );
        }
        if (!player.hasPermission("rookiecrates.open.*")
                && !player.hasPermission("rookiecrates.open." + crate.id())) {
            throw reject("你没有打开该宝箱的权限。" );
        }
        if (scenes.hasActiveSession(player.getUniqueId())) {
            throw reject("你已经处于开箱场景中。" );
        }
        if (crate.keyItemBlob() == null) {
            throw reject("该宝箱尚未设置钥匙模板。" );
        }
        if (context.rewards().isEmpty()) {
            throw reject("该宝箱没有可抽取的奖励。" );
        }
        for (RewardBundle reward : context.rewards()) {
            if (reward.definition().weight() <= 0.0D) {
                throw reject("奖池包含权重为零的奖励：" + reward.definition().id());
            }
            if (reward.items().isEmpty() && reward.commands().isEmpty()) {
                throw reject("奖励没有发放内容：" + reward.definition().id());
            }
        }

        CratePlacement placement = resolvePlacement(context);
        ItemStack keyTemplate = itemCodec.decode(crate.keyItemBlob());
        int requiredKeys = drawType.draws();
        double price = drawType == DrawType.SEVEN ? crate.sevenPrice() : crate.singlePrice();
        PaymentService.Quote quote = payments.quote(
                player,
                crate.id(),
                requiredKeys,
                context.playerState().virtualKeys(),
                price
        );
        if (!quote.keySatisfied()) {
            throw reject("钥匙不足；本次需要 " + requiredKeys + " 把。" );
        }
        if (!quote.economyAvailable() && price > 0.0D) {
            throw reject("服务器没有可用的 Vault 经济服务，付费宝箱已拒绝开启。" );
        }
        if (!quote.economySatisfied()) {
            throw reject("余额不足；本次需要 " + price + "。" );
        }
        try {
            PaymentService.Receipt receipt = payments.charge(
                    player, crate.id(), keyTemplate, quote);
            return new ChargedOpen(player.getUniqueId(), drawType.draws(), context, placement, receipt);
        } catch (PaymentService.PaymentException exception) {
            throw reject(switch (exception.reason()) {
                case INSUFFICIENT_KEYS -> "钥匙不足或背包中的钥匙发生了变化。";
                case ECONOMY_UNAVAILABLE -> "服务器没有可用的 Vault 经济服务。";
                case INSUFFICIENT_MONEY -> "余额不足。";
                case WITHDRAW_FAILED -> "经济扣款失败：" + exception.getMessage();
            });
        }
    }

    private CompletableFuture<DrawCommit> persistWithRefund(ChargedOpen charged) {
        CompletableFuture<DrawCommit> result = new CompletableFuture<>();
        persistDraw(charged).whenComplete((commit, failure) -> {
            if (failure == null) {
                result.complete(commit);
                return;
            }
            Throwable cause = unwrap(failure);
            refundFailedPayment(charged).whenComplete((ignored, refundFailure) -> {
                if (refundFailure != null) {
                    cause.addSuppressed(unwrap(refundFailure));
                }
                result.completeExceptionally(cause);
            });
        });
        return result;
    }

    private CompletableFuture<Void> refundFailedPayment(ChargedOpen charged) {
        return onMainThread(() -> {
                Player player = Bukkit.getPlayer(charged.playerId());
                if (player != null) {
                    PaymentService.RefundResult refund = payments.refund(player, charged.receipt());
                    if (!refund.moneyRefunded()) {
                        throw new IllegalStateException("Vault refund failed: " + refund.message());
                    }
                    return 0;
                }
                var money = payments.refundMoney(
                        Bukkit.getOfflinePlayer(charged.playerId()),
                        charged.receipt()
                );
                if (!money.success()) {
                    throw new IllegalStateException("Offline Vault refund failed: " + money.message());
                }
                return charged.receipt().physicalKeys();
            }).thenCompose(physicalToConvert -> {
                if (physicalToConvert <= 0) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    return database.run(dao -> dao.addVirtualKeys(
                            charged.playerId(),
                            charged.context().crate().id(),
                            physicalToConvert,
                            System.currentTimeMillis()
                    ));
                } catch (RuntimeException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            });
    }

    private CompletableFuture<DrawCommit> persistDraw(ChargedOpen charged) {
        return database.transaction(dao -> {
            long now = System.currentTimeMillis();
            CrateDefinition crate = dao.findCrate(charged.context().crate().id())
                    .orElseThrow(() -> reject("宝箱在开箱过程中被删除。" ));
            List<RewardBundle> rewards = dao.listEnabledRewards(crate.id());
            if (!sameCrateConfiguration(charged.context().crate(), crate)
                    || !sameRewardPool(charged.context().rewards(), rewards)) {
                throw reject("宝箱配置在付款过程中发生变化，本次已取消并退款，请重新开箱。" );
            }
            PlayerCrateState current = dao.getOrCreatePlayerState(charged.playerId(), crate.id(), now);
            int virtualToUse = charged.receipt().virtualKeys();
            if (virtualToUse > 0
                    && !dao.consumeVirtualKeys(charged.playerId(), crate.id(), virtualToUse, now)) {
                throw reject("虚拟钥匙余额发生变化，请重试。" );
            }

            List<PitySelector.Candidate<RewardBundle>> candidates = rewards.stream()
                    .map(reward -> new PitySelector.Candidate<>(
                            reward,
                            reward.definition().rarity(),
                            reward.definition().weight()
                    ))
                    .toList();
            List<PitySelector.Draw<RewardBundle>> draws;
            try {
                draws = selector.drawMany(
                        candidates,
                        new PitySelector.Progress(current.pityA(), current.pityS()),
                        new PitySelector.Policy(crate.guaranteeA(), crate.guaranteeS()),
                        charged.drawCount()
                );
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw reject("奖池或保底配置无效：" + exception.getMessage());
            }
            PitySelector.Progress finalProgress = draws.getLast().nextProgress();
            dao.updatePlayerState(new PlayerCrateState(
                    charged.playerId(),
                    crate.id(),
                    current.virtualKeys() - virtualToUse,
                    finalProgress.sinceA(),
                    finalProgress.sinceS(),
                    current.totalOpens() + charged.drawCount(),
                    now
            ));

            UUID transactionId = UUID.randomUUID();
            dao.createTransaction(new OpenTransaction(
                    transactionId,
                    charged.playerId(),
                    crate.id(),
                    charged.drawCount(),
                    OpenTransactionStatus.PENDING,
                    now,
                    null,
                    null
            ));
            List<RewardBundle> selectedRewards = new ArrayList<>(draws.size());
            for (int index = 0; index < draws.size(); index++) {
                RewardBundle selected = draws.get(index).candidate().value();
                int resultIndex = index + 1;
                selectedRewards.add(selected);
                dao.addOpenResult(new OpenResult(
                        transactionId,
                        resultIndex,
                        selected.definition().id(),
                        selected.definition().rarity(),
                        false
                ));
                for (RewardItem item : selected.items()) {
                    dao.enqueueDelivery(new PendingDelivery(
                            0,
                            transactionId,
                            resultIndex,
                            charged.playerId(),
                            selected.definition().id(),
                            item.itemBlob(),
                            item.amount(),
                            null,
                            PendingDeliveryStatus.PENDING,
                            0,
                            null,
                            now,
                            null
                    ));
                }
                for (RewardCommand command : selected.commands()) {
                    dao.enqueueDelivery(new PendingDelivery(
                            0,
                            transactionId,
                            resultIndex,
                            charged.playerId(),
                            selected.definition().id(),
                            null,
                            0,
                            command.command(),
                            PendingDeliveryStatus.PENDING,
                            0,
                            null,
                            now,
                            null
                    ));
                }
            }
            if (!dao.transitionTransaction(
                    transactionId,
                    OpenTransactionStatus.PENDING,
                    OpenTransactionStatus.DRAWN,
                    null,
                    null
            )) {
                throw new IllegalStateException("Could not commit draw transaction state");
            }
            return new DrawCommit(
                    transactionId,
                    crate,
                    charged.placement(),
                    charged.context().profile(),
                    List.copyOf(selectedRewards)
            );
        });
    }

    /**
     * Vault and inventory cannot participate in the SQLite transaction. Comparing the complete
     * paid snapshot here prevents an administrator edit from producing an old-price/new-pool draw.
     * All database work is serialized, so once this comparison succeeds the same transaction owns
     * the configuration until its result snapshots have been committed.
     */
    private static boolean sameCrateConfiguration(CrateDefinition expected, CrateDefinition actual) {
        return expected.id().equals(actual.id())
                && expected.displayName().equals(actual.displayName())
                && expected.enabled() == actual.enabled()
                && Arrays.equals(expected.keyItemBlob(), actual.keyItemBlob())
                && Double.compare(expected.singlePrice(), actual.singlePrice()) == 0
                && Double.compare(expected.sevenPrice(), actual.sevenPrice()) == 0
                && expected.guaranteeA() == actual.guaranteeA()
                && expected.guaranteeS() == actual.guaranteeS()
                && Objects.equals(expected.sceneProfileId(), actual.sceneProfileId())
                && expected.broadcastRarity() == actual.broadcastRarity()
                && expected.skipAllowed() == actual.skipAllowed()
                && Double.compare(expected.interactionWidth(), actual.interactionWidth()) == 0
                && Double.compare(expected.interactionHeight(), actual.interactionHeight()) == 0
                && expected.idleAnimation().equals(actual.idleAnimation())
                && expected.openAnimation().equals(actual.openAnimation());
    }

    private static boolean sameRewardPool(List<RewardBundle> expected, List<RewardBundle> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        Map<String, RewardBundle> byId = new HashMap<>();
        for (RewardBundle reward : actual) {
            byId.put(reward.definition().id(), reward);
        }
        for (RewardBundle reward : expected) {
            RewardBundle current = byId.get(reward.definition().id());
            if (current == null || !sameReward(reward, current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameReward(RewardBundle expected, RewardBundle actual) {
        if (!expected.definition().equals(actual.definition())
                || expected.items().size() != actual.items().size()
                || expected.commands().size() != actual.commands().size()) {
            return false;
        }
        for (int index = 0; index < expected.items().size(); index++) {
            RewardItem left = expected.items().get(index);
            RewardItem right = actual.items().get(index);
            if (left.id() != right.id()
                    || !left.rewardId().equals(right.rewardId())
                    || left.amount() != right.amount()
                    || !Arrays.equals(left.itemBlob(), right.itemBlob())) {
                return false;
            }
        }
        return expected.commands().equals(actual.commands());
    }

    private boolean startScene(Player player, DrawCommit commit) {
        List<SceneReward> displayRewards = commit.rewards().stream()
                .map(this::toSceneReward)
                .toList();
        SceneRequest request = new SceneRequest(
                commit.transactionId(),
                commit.placement(),
                displayRewards,
                commit.crate().openAnimation(),
                commit.crate().skipAllowed(),
                () -> finishCommittedDraw(player, commit, null),
                failure -> finishCommittedDraw(player, commit, failure)
        );
        return scenes.play(player, request);
    }

    private SceneReward toSceneReward(RewardBundle reward) {
        ItemStack display;
        if (reward.items().isEmpty()) {
            display = new ItemStack(Material.COMMAND_BLOCK);
        } else {
            display = itemCodec.decode(reward.items().getFirst().itemBlob());
            display.setAmount(1);
        }
        return new SceneReward(
                display,
                reward.definition().displayName(),
                lootModels.modelFor(reward.definition().rarity())
        );
    }

    private void finishCommittedDraw(Player player, DrawCommit commit, Throwable sceneFailure) {
        if (!finalizingTransactions.add(commit.transactionId())) {
            return;
        }
        boolean defer = shouldDeferDelivery(player, sceneFailure);
        if (defer) {
            CompletableFuture<Void> deferral;
            try {
                deferral = database.run(dao -> {
                    OpenTransaction transaction = dao.findTransaction(commit.transactionId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Missing opening transaction " + commit.transactionId()));
                    if (transaction.status() != OpenTransactionStatus.DRAWN
                            || !dao.transitionTransaction(
                            commit.transactionId(),
                            OpenTransactionStatus.DRAWN,
                            OpenTransactionStatus.RECOVERY_REQUIRED,
                            null,
                            sceneFailure == null ? "player unavailable" : describe(sceneFailure)
                    )) {
                        throw new IllegalStateException("Could not defer opening transaction "
                                + commit.transactionId() + " from " + transaction.status());
                    }
                });
            } catch (RuntimeException exception) {
                deferral = CompletableFuture.failedFuture(exception);
            }
            deferral.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    plugin.getLogger().severe("Could not persist deferred delivery for transaction "
                            + commit.transactionId() + ": " + describe(failure));
                }
                finalizingTransactions.remove(commit.transactionId());
                playerLocks.unlock(player.getUniqueId());
            });
            return;
        }

        CompletableFuture<RewardDeliveryService.DeliveryReport> delivery;
        try {
            delivery = database.submit(dao -> {
                OpenTransaction transaction = dao.findTransaction(commit.transactionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing opening transaction " + commit.transactionId()));
                if (transaction.status() != OpenTransactionStatus.DRAWN
                        || !dao.transitionTransaction(
                        commit.transactionId(),
                        OpenTransactionStatus.DRAWN,
                        OpenTransactionStatus.DELIVERING,
                        null,
                        sceneFailure == null ? null : describe(sceneFailure)
                )) {
                    throw new IllegalStateException("Transaction is not claimable from DRAWN: "
                            + commit.transactionId() + " (" + transaction.status() + ")");
                }
                return null;
            }).thenCompose(ignored -> deliveries.deliverTransaction(player, commit.transactionId()));
        } catch (RuntimeException exception) {
            delivery = CompletableFuture.failedFuture(exception);
        }

        delivery.handle(FinalizationOutcome::new)
                .thenCompose(outcome -> onMainThread(() -> {
                    if (outcome.failure() != null) {
                        plugin.getLogger().severe("Reward delivery failed for transaction "
                                + commit.transactionId() + ": " + describe(outcome.failure()));
                        if (player.isOnline()) {
                            player.sendMessage("§c奖励发放暂时失败，请稍后使用 /rc claim 重试。" );
                        }
                    } else {
                        broadcast(commit, player);
                        if (outcome.report().hasPending() && player.isOnline()) {
                            messages.send(player, "pending-created");
                        }
                    }
                    return null;
                }))
                .whenComplete((ignored, uiFailure) -> {
                    if (uiFailure != null) {
                        plugin.getLogger().severe("Could not finish opening transaction "
                                + commit.transactionId() + " on the server thread: " + describe(uiFailure));
                    }
                    finalizingTransactions.remove(commit.transactionId());
                    playerLocks.unlock(player.getUniqueId());
                });
    }

    private boolean shouldDeferDelivery(Player player, Throwable failure) {
        if (!player.isOnline() || player.isDead()) {
            return true;
        }
        Throwable cause = failure == null ? null : unwrap(failure);
        if (cause instanceof SceneAbortedException aborted) {
            return aborted.reason() != SceneAbortReason.INTERNAL_ERROR;
        }
        return false;
    }

    private void broadcast(DrawCommit commit, Player player) {
        String format = plugin.getConfig().getString(
                "broadcast.format",
                "<gold><player></gold> 在 <yellow><crate></yellow> 中抽到了 <light_purple><reward></light_purple>！"
        );
        Map<String, BroadcastReward> announcements = new LinkedHashMap<>();
        for (RewardBundle reward : commit.rewards()) {
            boolean thresholdHit = commit.crate().broadcastRarity() != null
                    && reward.definition().rarity().isAtLeast(commit.crate().broadcastRarity());
            if (!reward.definition().broadcast() && !thresholdHit) {
                continue;
            }
            announcements.compute(reward.definition().id(), (ignored, existing) -> existing == null
                    ? new BroadcastReward(reward.definition().displayName(), 1)
                    : new BroadcastReward(existing.displayName(), existing.count() + 1));
        }
        for (BroadcastReward reward : announcements.values()) {
            String rewardText = reward.count() == 1
                    ? reward.displayName()
                    : reward.displayName() + " ×" + reward.count();
            Bukkit.broadcast(messages.parse(format, Map.of(
                    "player", player.getName(),
                    "crate", commit.crate().displayName(),
                    "reward", rewardText
            )));
        }
    }

    private CratePlacement resolvePlacement(OpenContext context) {
        if (context.profile() == null) {
            throw reject("该宝箱尚未设置场景配置。" );
        }
        Placement placement = context.placements().stream()
                .filter(candidate -> candidate.placementId().equals(context.preferredPlacement()))
                .findFirst()
                .orElseGet(() -> context.placements().stream()
                        .min(Comparator.comparing(Placement::placementId))
                        .orElseThrow(() -> reject("该宝箱尚未放置模型入口。" )));

        Map<ScenePointKind, Map<Integer, ScenePoint>> points = new EnumMap<>(ScenePointKind.class);
        for (ScenePoint point : context.scenePoints()) {
            points.computeIfAbsent(point.kind(), ignored -> new HashMap<>())
                    .put(point.pointIndex(), point);
        }
        ScenePoint cratePoint = point(points, ScenePointKind.CRATE, 1);
        ScenePoint cameraPoint = point(points, ScenePointKind.CAMERA, 1);
        List<Location> lootLocations = new ArrayList<>(7);
        for (int index = 1; index <= 7; index++) {
            lootLocations.add(toLocation(point(points, ScenePointKind.LOOT, index)));
        }
        return new CratePlacement(
                context.crate().id(),
                placement.placementId(),
                toLocation(placement),
                toLocation(cratePoint),
                toLocation(cameraPoint),
                lootLocations,
                (float) context.crate().interactionWidth(),
                (float) context.crate().interactionHeight(),
                context.profile().crateModel(),
                context.crate().idleAnimation()
        );
    }

    private ScenePoint point(
            Map<ScenePointKind, Map<Integer, ScenePoint>> points,
            ScenePointKind kind,
            int index
    ) {
        ScenePoint point = points.getOrDefault(kind, Map.of()).get(index);
        if (point == null) {
            String name = kind == ScenePointKind.LOOT ? "loot" + index : kind.name().toLowerCase();
            throw reject("场景点未设置完整，缺少 " + name + "。" );
        }
        return point;
    }

    private Location toLocation(Placement placement) {
        World world = Bukkit.getWorld(placement.world());
        if (world == null) {
            throw reject("入口世界未加载：" + placement.world());
        }
        return new Location(world, placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
    }

    private Location toLocation(ScenePoint point) {
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            throw reject("场景世界未加载：" + point.world());
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
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

    private static OpeningRejectedException reject(String message) {
        return new OpeningRejectedException(message);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private record OpenContext(
            CrateDefinition crate,
            SceneProfile profile,
            List<ScenePoint> scenePoints,
            List<Placement> placements,
            List<RewardBundle> rewards,
            PlayerCrateState playerState,
            String preferredPlacement
    ) { }

    private record ChargedOpen(
            UUID playerId,
            int drawCount,
            OpenContext context,
            CratePlacement placement,
            PaymentService.Receipt receipt
    ) { }

    private record DrawCommit(
            UUID transactionId,
            CrateDefinition crate,
            CratePlacement placement,
            SceneProfile profile,
            List<RewardBundle> rewards
    ) { }

    private record BroadcastReward(String displayName, int count) { }

    private record FinalizationOutcome(
            RewardDeliveryService.DeliveryReport report,
            Throwable failure
    ) { }

    private static final class OpeningRejectedException extends RuntimeException {
        private final String userMessage;

        private OpeningRejectedException(String userMessage) {
            super(userMessage);
            this.userMessage = userMessage;
        }

        private String userMessage() {
            return userMessage;
        }
    }
}
