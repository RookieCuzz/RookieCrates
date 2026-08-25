package com.cuzz.rookieCrates.gui.api;

import com.cuzz.rookieCrates.domain.Rarity;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous boundary between Bukkit inventory screens and the crate domain.
 * Implementations must never require callers to block the server thread.
 */
public interface CratesGuiFacade {

    CompletableFuture<List<CrateView>> listCrates(UUID viewer);

    CompletableFuture<Optional<CrateView>> getCrate(String crateId, UUID viewer);

    /**
     * Starts one atomic draw transaction. SEVEN charges exactly seven keys plus the configured
     * seven-draw package price and starts one scene in which LOOT_1..LOOT_7 appear together.
     */
    CompletableFuture<GuiResult> requestDraw(Player player, String crateId, DrawType drawType);

    CompletableFuture<GuiResult> claimPending(Player player);

    CompletableFuture<GuiResult> createCrate(String crateId, String displayName);

    CompletableFuture<GuiResult> deleteCrate(String crateId);

    CompletableFuture<GuiResult> saveCrate(CrateSettings settings);

    CompletableFuture<GuiResult> saveReward(String crateId, RewardSettings reward);

    CompletableFuture<GuiResult> deleteReward(String crateId, String rewardId);

    CompletableFuture<GuiResult> givePhysicalKeys(Player target, String crateId, int amount);

    CompletableFuture<GuiResult> addVirtualKeys(UUID target, String crateId, int amount);

    CompletableFuture<GuiResult> placeModelCrate(String crateId, Location location);

    CompletableFuture<GuiResult> removePlacement(String crateId);

    CompletableFuture<GuiResult> setScenePoint(String crateId, ScenePoint point, Location location);

    CompletableFuture<GuiResult> exportConfig();

    CompletableFuture<GuiResult> importConfig();

    enum DrawType {
        SINGLE(1),
        SEVEN(7);

        private final int draws;

        DrawType(int draws) {
            this.draws = draws;
        }

        public int draws() {
            return draws;
        }
    }

    enum ScenePoint {
        CRATE,
        CAMERA,
        LOOT_1,
        LOOT_2,
        LOOT_3,
        LOOT_4,
        LOOT_5,
        LOOT_6,
        LOOT_7
    }

    record GuiResult(boolean success, String message) {
        public GuiResult {
            message = message == null ? "" : message;
        }

        public static GuiResult success(String message) {
            return new GuiResult(true, message);
        }

        public static GuiResult failure(String message) {
            return new GuiResult(false, message);
        }
    }

    record PlayerStats(int virtualKeys, long totalDraws, int pityA, int pityS, int pendingRewards) {
        public PlayerStats {
            if (virtualKeys < 0 || totalDraws < 0 || pityA < 0 || pityS < 0 || pendingRewards < 0) {
                throw new IllegalArgumentException("Player statistics cannot be negative");
            }
        }
    }

    record CrateSettings(
            String id,
            String displayName,
            boolean enabled,
            ItemStack icon,
            ItemStack keyTemplate,
            double singlePrice,
            double sevenPrice,
            int pityA,
            int pityS,
            boolean broadcastS,
            boolean skipAllowed,
            double interactionWidth,
            double interactionHeight,
            String crateModel,
            String lootModel,
            String idleAnimation,
            String openAnimation
    ) {
        public CrateSettings {
            id = requireText(id, "id");
            displayName = requireText(displayName, "displayName");
            icon = cloneOrNull(icon);
            keyTemplate = cloneOrNull(keyTemplate);
            requireNonNegative(singlePrice, "singlePrice");
            requireNonNegative(sevenPrice, "sevenPrice");
            if (pityA <= 0 || pityS <= 0) {
                throw new IllegalArgumentException("Pity thresholds must be positive");
            }
            if (!Double.isFinite(interactionWidth) || interactionWidth <= 0.0D
                    || !Double.isFinite(interactionHeight) || interactionHeight <= 0.0D) {
                throw new IllegalArgumentException("Interaction dimensions must be finite and positive");
            }
            crateModel = requireText(crateModel, "crateModel");
            lootModel = requireText(lootModel, "lootModel");
            idleAnimation = requireText(idleAnimation, "idleAnimation");
            openAnimation = requireText(openAnimation, "openAnimation");
        }

        @Override
        public ItemStack icon() {
            return cloneOrNull(icon);
        }

        @Override
        public ItemStack keyTemplate() {
            return cloneOrNull(keyTemplate);
        }
    }

    /** Item reward and console commands may both be present. */
    record RewardSettings(
            String id,
            String displayName,
            ItemStack icon,
            ItemStack itemReward,
            List<String> consoleCommands,
            double weight,
            Rarity rarity,
            boolean broadcast
    ) {
        public RewardSettings {
            id = requireText(id, "id");
            displayName = requireText(displayName, "displayName");
            icon = cloneOrNull(icon);
            itemReward = cloneOrNull(itemReward);
            consoleCommands = consoleCommands == null
                    ? List.of()
                    : consoleCommands.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
            if (!Double.isFinite(weight) || weight <= 0.0D) {
                throw new IllegalArgumentException("Reward weight must be finite and positive");
            }
            rarity = Objects.requireNonNull(rarity, "rarity");
            if (itemReward == null && consoleCommands.isEmpty()) {
                throw new IllegalArgumentException("A reward needs an item, a command, or both");
            }
        }

        @Override
        public ItemStack icon() {
            return cloneOrNull(icon);
        }

        @Override
        public ItemStack itemReward() {
            return cloneOrNull(itemReward);
        }
    }

    record RewardView(RewardSettings settings, double probability) {
        public RewardView {
            settings = Objects.requireNonNull(settings, "settings");
            if (!Double.isFinite(probability) || probability < 0.0D || probability > 1.0D) {
                throw new IllegalArgumentException("probability must be between 0 and 1");
            }
        }
    }

    record CrateView(
            CrateSettings settings,
            List<RewardView> rewards,
            PlayerStats playerStats,
            boolean economyAvailable,
            double economyBalance,
            int physicalKeys
    ) {
        public CrateView {
            settings = Objects.requireNonNull(settings, "settings");
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
            playerStats = Objects.requireNonNull(playerStats, "playerStats");
            if (!Double.isFinite(economyBalance) || physicalKeys < 0) {
                throw new IllegalArgumentException("Invalid balance or physical key count");
            }
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
