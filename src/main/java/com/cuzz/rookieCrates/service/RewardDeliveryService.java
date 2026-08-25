package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.domain.OpenTransaction;
import com.cuzz.rookieCrates.domain.PendingDelivery;
import com.cuzz.rookieCrates.domain.PendingDeliveryStatus;
import com.cuzz.rookieCrates.domain.RewardBundle;
import com.cuzz.rookieCrates.storage.SQLiteDatabase;
import com.cuzz.rookieCrates.util.ItemStackCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Delivers the immutable item/command snapshots recorded with a draw. Database
 * pages are small, but a normal claim keeps paging until every currently
 * claimable row has been considered. FAILED command rows are deliberately not
 * selected again; an administrator must explicitly reset one after reviewing
 * whether its external side effect happened.
 */
public final class RewardDeliveryService {

    private static final int CLAIM_PAGE_SIZE = 128;
    private static final String MANUAL_REVIEW_PREFIX = "MANUAL_REVIEW: ";

    private final JavaPlugin plugin;
    private final SQLiteDatabase database;
    private final ItemStackCodec itemCodec;

    public RewardDeliveryService(JavaPlugin plugin, SQLiteDatabase database, ItemStackCodec itemCodec) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.itemCodec = Objects.requireNonNull(itemCodec, "itemCodec");
    }

    /** May be called from any thread; all Bukkit work is marshalled to the server thread. */
    public CompletableFuture<DeliveryReport> deliver(Player player) {
        return deliverScoped(Objects.requireNonNull(player, "player"), null);
    }

    /**
     * Delivers only snapshots belonging to {@code transactionId}. Ownership is
     * checked before any side effect, so a transaction id cannot be used to send
     * another player's rewards.
     */
    public CompletableFuture<DeliveryReport> deliver(Player player, UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return deliverScoped(Objects.requireNonNull(player, "player"), transactionId);
    }

    /** Explicitly named alias for callers which prefer a transaction-oriented API. */
    public CompletableFuture<DeliveryReport> deliverTransaction(Player player, UUID transactionId) {
        return deliver(player, transactionId);
    }

    private CompletableFuture<DeliveryReport> deliverScoped(Player player, UUID transactionId) {
        UUID playerId = player.getUniqueId();
        return onMainThread(() -> isPlayerAvailable(player)).thenCompose(available -> {
            if (!available) {
                return CompletableFuture.completedFuture(DeliveryReport.unavailable());
            }
            return prepareScope(playerId, transactionId)
                    .thenCompose(ignored -> deliverPages(
                            player,
                            transactionId,
                            DeliveryCursor.START,
                            DeliveryReport.empty()
                    ))
                    .thenCompose(report -> reconcileAndRefreshReport(playerId, transactionId, report));
        });
    }

    private CompletableFuture<Void> prepareScope(UUID playerId, UUID transactionId) {
        return database.transaction(dao -> {
            if (transactionId == null) {
                dao.prepareTransactionsForDelivery(playerId);
                return null;
            }

            OpenTransaction transaction = dao.findTransaction(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown opening transaction " + transactionId
                    ));
            if (!transaction.playerUuid().equals(playerId)) {
                throw new IllegalArgumentException(
                        "Opening transaction " + transactionId + " does not belong to player " + playerId
                );
            }
            dao.prepareTransactionForDelivery(playerId, transactionId);
            return null;
        });
    }

    private CompletableFuture<DeliveryReport> deliverPages(
            Player player,
            UUID transactionId,
            DeliveryCursor cursor,
            DeliveryReport accumulated
    ) {
        return loadPage(player.getUniqueId(), transactionId, cursor).thenCompose(deliveries -> {
            if (deliveries.isEmpty()) {
                return CompletableFuture.completedFuture(accumulated);
            }

            PendingDelivery last = deliveries.getLast().delivery();
            DeliveryCursor nextCursor = DeliveryCursor.after(last);
            return onMainThread(() -> processOnMainThread(player, deliveries))
                    .thenCompose(this::persist)
                    .thenCompose(pageReport -> {
                        DeliveryReport merged = accumulated.plus(pageReport);
                        if (pageReport.playerOffline()) {
                            return CompletableFuture.completedFuture(merged);
                        }
                        return deliverPages(player, transactionId, nextCursor, merged);
                    });
        });
    }

    private CompletableFuture<List<PreparedDelivery>> loadPage(
            UUID playerId,
            UUID transactionId,
            DeliveryCursor cursor
    ) {
        return database.submit(dao -> {
            List<PendingDelivery> deliveries = transactionId == null
                    ? dao.listClaimableDeliveries(
                            playerId, cursor.phase(), cursor.id(), CLAIM_PAGE_SIZE
                    )
                    : dao.listClaimableDeliveries(
                            playerId, transactionId, cursor.phase(), cursor.id(), CLAIM_PAGE_SIZE
                    );
            Map<UUID, String> crateIds = new HashMap<>();
            Map<String, String> rewardNames = new HashMap<>();
            List<PreparedDelivery> prepared = new ArrayList<>(deliveries.size());
            for (PendingDelivery delivery : deliveries) {
                String crateId = crateIds.computeIfAbsent(delivery.transactionId(), id -> {
                    try {
                        return dao.findTransaction(id).map(OpenTransaction::crateId).orElse("unknown");
                    } catch (SQLException exception) {
                        throw new DeliveryLookupException(exception);
                    }
                });
                String rewardName = rewardNames.computeIfAbsent(delivery.rewardId(), rewardId -> {
                    try {
                        return dao.findReward(rewardId)
                                .map(RewardBundle::definition)
                                .map(definition -> definition.displayName())
                                .orElse(rewardId);
                    } catch (SQLException exception) {
                        throw new DeliveryLookupException(exception);
                    }
                });
                prepared.add(new PreparedDelivery(delivery, crateId, rewardName));
            }
            return List.copyOf(prepared);
        });
    }

    private ProcessedBatch processOnMainThread(Player player, List<PreparedDelivery> deliveries) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Reward delivery must run on the Bukkit main thread");
        }

        List<DeliveryUpdate> updates = new ArrayList<>(deliveries.size());
        int insertedItems = 0;
        for (int index = 0; index < deliveries.size(); index++) {
            if (!isPlayerAvailable(player)) {
                return new ProcessedBatch(
                        List.copyOf(updates),
                        insertedItems,
                        deliveries.size() - index,
                        true
                );
            }

            PreparedDelivery prepared = deliveries.get(index);
            PendingDelivery delivery = prepared.delivery();
            if (delivery.itemBlob() != null) {
                try {
                    ItemDeliveryResult result = insertItem(player, delivery);
                    insertedItems += result.insertedAmount();
                    if (result.remainingAmount() == 0) {
                        updates.add(DeliveryUpdate.delivered(delivery));
                    } else {
                        updates.add(DeliveryUpdate.remaining(
                                delivery,
                                result.remainingAmount(),
                                "背包空间不足"
                        ));
                    }
                } catch (RuntimeException exception) {
                    updates.add(DeliveryUpdate.failed(delivery, manualReviewReason(
                            "物品发放结果不确定", exception
                    )));
                }
                continue;
            }

            try {
                String command = replacePlaceholders(delivery.command(), player, prepared);
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (success) {
                    // A successful command is committed even if it synchronously kicks or kills
                    // the player; retrying that external side effect would be less safe.
                    updates.add(DeliveryUpdate.delivered(delivery));
                } else {
                    updates.add(DeliveryUpdate.failed(
                            delivery,
                            MANUAL_REVIEW_PREFIX + "控制台命令返回失败，禁止自动重试: " + command
                    ));
                }
            } catch (RuntimeException exception) {
                updates.add(DeliveryUpdate.failed(delivery, manualReviewReason(
                        "控制台命令执行异常，结果不确定且禁止自动重试", exception
                )));
            }

            // Commands may synchronously kick/kill the target. The current row already has a
            // safe terminal decision above; every later row stays untouched and PENDING.
            if (!isPlayerAvailable(player)) {
                return new ProcessedBatch(
                        List.copyOf(updates),
                        insertedItems,
                        deliveries.size() - index - 1,
                        true
                );
            }
        }
        return new ProcessedBatch(List.copyOf(updates), insertedItems, 0, false);
    }

    private ItemDeliveryResult insertItem(Player player, PendingDelivery delivery) {
        ItemStack template = itemCodec.decode(delivery.itemBlob());
        template.setAmount(1);
        int maxStack = Math.max(1, template.getMaxStackSize());
        int remaining = delivery.amount();
        int inserted = 0;
        while (remaining > 0) {
            int requested = Math.min(maxStack, remaining);
            ItemStack part = template.clone();
            part.setAmount(requested);
            int leftover = player.getInventory().addItem(part).values().stream()
                    .mapToInt(ItemStack::getAmount)
                    .sum();
            int accepted = requested - leftover;
            inserted += accepted;
            remaining -= accepted;
            if (accepted == 0) {
                break;
            }
        }
        return new ItemDeliveryResult(inserted, remaining);
    }

    private CompletableFuture<DeliveryReport> persist(ProcessedBatch batch) {
        if (batch.updates().isEmpty()) {
            return CompletableFuture.completedFuture(new DeliveryReport(
                    0,
                    batch.unprocessedRows(),
                    0,
                    batch.insertedItems(),
                    batch.playerUnavailable()
            ));
        }

        long now = System.currentTimeMillis();
        return database.transaction(dao -> {
            int deliveredRows = 0;
            int remainingRows = batch.unprocessedRows();
            int failedRows = 0;
            for (DeliveryUpdate update : batch.updates()) {
                PendingDelivery delivery = update.delivery();
                boolean persisted = switch (update.kind()) {
                    case DELIVERED -> dao.markPendingDeliveryDelivered(delivery.id(), now);
                    case REMAINING -> dao.updatePendingDeliveryRemaining(
                            delivery.id(),
                            update.remainingAmount(),
                            delivery.attempts() + 1,
                            update.error()
                    );
                    case FAILED -> dao.markPendingDeliveryFailed(delivery.id(), update.error());
                };
                if (!persisted) {
                    throw new SQLException(
                            "Delivery row " + delivery.id() + " was no longer PENDING while persisting its outcome"
                    );
                }
                switch (update.kind()) {
                    case DELIVERED -> deliveredRows++;
                    case REMAINING -> remainingRows++;
                    case FAILED -> failedRows++;
                }
            }
            return new DeliveryReport(
                    deliveredRows,
                    remainingRows,
                    failedRows,
                    batch.insertedItems(),
                    batch.playerUnavailable()
            );
        });
    }

    private CompletableFuture<DeliveryReport> reconcileAndRefreshReport(
            UUID playerId,
            UUID transactionId,
            DeliveryReport report
    ) {
        long now = System.currentTimeMillis();
        return database.transaction(dao -> {
            if (transactionId == null) {
                dao.completeDeliverableTransactions(playerId, now);
                return report.withBacklog(
                        dao.countDeliveries(playerId, PendingDeliveryStatus.PENDING),
                        dao.countDeliveries(playerId, PendingDeliveryStatus.FAILED)
                );
            } else {
                dao.completeDeliverableTransaction(playerId, transactionId, now);
                return report.withBacklog(
                        dao.countDeliveries(playerId, transactionId, PendingDeliveryStatus.PENDING),
                        dao.countDeliveries(playerId, transactionId, PendingDeliveryStatus.FAILED)
                );
            }
        });
    }

    private String replacePlaceholders(String raw, Player player, PreparedDelivery prepared) {
        Map<String, String> values = Map.of(
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "crate", prepared.crateId(),
                "reward", prepared.rewardName(),
                "amount", Integer.toString(Math.max(1, prepared.delivery().amount()))
        );
        String command = Objects.requireNonNull(raw, "command");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            command = command.replace("{" + entry.getKey() + "}", entry.getValue());
            command = command.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return command.trim();
    }

    private static String manualReviewReason(String context, RuntimeException exception) {
        String detail = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return MANUAL_REVIEW_PREFIX + context + ": " + detail;
    }

    private static boolean isPlayerAvailable(Player player) {
        return player.isOnline() && !player.isDead();
    }

    private <T> CompletableFuture<T> onMainThread(java.util.function.Supplier<T> supplier) {
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

    public record DeliveryReport(
            int deliveredRows,
            int remainingRows,
            int failedRows,
            int insertedItems,
            boolean playerOffline
    ) {
        private static DeliveryReport empty() {
            return new DeliveryReport(0, 0, 0, 0, false);
        }

        private static DeliveryReport unavailable() {
            return new DeliveryReport(0, 0, 0, 0, true);
        }

        private DeliveryReport plus(DeliveryReport other) {
            return new DeliveryReport(
                    Math.addExact(deliveredRows, other.deliveredRows),
                    Math.addExact(remainingRows, other.remainingRows),
                    Math.addExact(failedRows, other.failedRows),
                    Math.addExact(insertedItems, other.insertedItems),
                    playerOffline || other.playerOffline
            );
        }

        /** Replaces page-local remainder counts with the authoritative persisted backlog. */
        private DeliveryReport withBacklog(int pendingRows, int manualReviewRows) {
            if (pendingRows < 0 || manualReviewRows < 0) {
                throw new IllegalArgumentException("delivery backlog counts must be non-negative");
            }
            return new DeliveryReport(
                    deliveredRows,
                    pendingRows,
                    manualReviewRows,
                    insertedItems,
                    playerOffline
            );
        }

        /** The compatibility name also covers a player who became dead during a claim. */
        public boolean playerUnavailable() {
            return playerOffline;
        }

        public boolean hasPending() {
            return remainingRows > 0 || failedRows > 0 || playerOffline;
        }
    }

    private record PreparedDelivery(PendingDelivery delivery, String crateId, String rewardName) { }

    private record ItemDeliveryResult(int insertedAmount, int remainingAmount) { }

    private record DeliveryCursor(int phase, long id) {
        private static final DeliveryCursor START = new DeliveryCursor(0, 0);

        private static DeliveryCursor after(PendingDelivery delivery) {
            return new DeliveryCursor(delivery.itemBlob() == null ? 1 : 0, delivery.id());
        }
    }

    private enum UpdateKind { DELIVERED, REMAINING, FAILED }

    private record DeliveryUpdate(
            PendingDelivery delivery,
            UpdateKind kind,
            int remainingAmount,
            String error
    ) {
        private static DeliveryUpdate delivered(PendingDelivery delivery) {
            return new DeliveryUpdate(delivery, UpdateKind.DELIVERED, 0, null);
        }

        private static DeliveryUpdate remaining(PendingDelivery delivery, int amount, String error) {
            return new DeliveryUpdate(delivery, UpdateKind.REMAINING, amount, error);
        }

        private static DeliveryUpdate failed(PendingDelivery delivery, String error) {
            return new DeliveryUpdate(delivery, UpdateKind.FAILED, 0, error);
        }
    }

    private record ProcessedBatch(
            List<DeliveryUpdate> updates,
            int insertedItems,
            int unprocessedRows,
            boolean playerUnavailable
    ) { }

    private static final class DeliveryLookupException extends RuntimeException {
        private DeliveryLookupException(SQLException cause) {
            super(cause);
        }
    }
}
