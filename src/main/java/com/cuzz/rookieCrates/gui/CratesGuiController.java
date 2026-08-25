package com.cuzz.rookieCrates.gui;

import com.cuzz.rookieCrates.config.LootModelPalette;
import com.cuzz.rookieCrates.domain.Rarity;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.CrateSettings;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.CrateView;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.DrawType;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.GuiResult;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.RewardSettings;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.RewardView;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.ScenePoint;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.cuzz.rookieCrates.gui.ScreenHolder.Screen;

/** Renders and handles every native Bukkit inventory screen used by RookieCrates. */
public final class CratesGuiController implements Listener {

    public static final String USE_PERMISSION = "rookiecrates.use";
    public static final String ADMIN_PERMISSION = "rookiecrates.admin";
    private static final int LIST_PAGE_SIZE = 45;
    private static final int REWARD_PAGE_SIZE = 27;

    private final JavaPlugin plugin;
    private final CratesGuiFacade facade;
    private final ChatInputManager chatInputs;
    private final LootModelPalette lootModels;

    public CratesGuiController(
            JavaPlugin plugin,
            CratesGuiFacade facade,
            ChatInputManager chatInputs,
            LootModelPalette lootModels
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.chatInputs = Objects.requireNonNull(chatInputs, "chatInputs");
        this.lootModels = Objects.requireNonNull(lootModels, "lootModels");
    }

    public void openPlayerList(Player player, int page) {
        if (!requirePermission(player, USE_PERMISSION)) {
            return;
        }
        showLoading(player, Screen.PLAYER_LIST, null, page);
        await(player, () -> facade.listCrates(player.getUniqueId()), crates -> {
            List<CrateView> visible = crates.stream().filter(crate -> crate.settings().enabled()).toList();
            ScreenHolder holder = screen(player, Screen.PLAYER_LIST, null, null, page, 54, "&8选择宝箱");
            Inventory inventory = holder.getInventory();
            GuiItems.fill(inventory);
            fillCrateList(holder, visible, page, false);
            inventory.setItem(45, GuiItems.item(Material.ARROW, "&e上一页"));
            inventory.setItem(49, GuiItems.item(Material.BARRIER, "&c关闭"));
            inventory.setItem(50, GuiItems.item(Material.CHEST_MINECART, "&a领取待发奖励"));
            inventory.setItem(53, GuiItems.item(Material.ARROW, "&e下一页"));
            player.openInventory(inventory);
        });
    }

    public void openPlayerCrate(Player player, String crateId, int page) {
        if (!requirePermission(player, USE_PERMISSION)) {
            return;
        }
        showLoading(player, Screen.PLAYER_CRATE, crateId, page);
        await(player, () -> facade.getCrate(crateId, player.getUniqueId()), optional -> {
            if (optional.isEmpty() || !optional.get().settings().enabled()) {
                player.sendMessage(GuiItems.color("&c该宝箱不存在或当前未启用。"));
                openPlayerList(player, 0);
                return;
            }
            renderPlayerCrate(player, optional.get(), page);
        });
    }

    public void openAdminList(Player player, int page) {
        if (!requirePermission(player, ADMIN_PERMISSION)) {
            return;
        }
        showLoading(player, Screen.ADMIN_LIST, null, page);
        await(player, () -> facade.listCrates(player.getUniqueId()), crates -> {
            ScreenHolder holder = screen(player, Screen.ADMIN_LIST, null, null, page, 54, "&4RookieCrates 管理");
            Inventory inventory = holder.getInventory();
            GuiItems.fill(inventory);
            fillCrateList(holder, crates, page, true);
            inventory.setItem(45, GuiItems.item(Material.ARROW, "&e上一页"));
            inventory.setItem(47, GuiItems.item(Material.WRITABLE_BOOK, "&e导入", "&7从插件导入文件覆盖/合并配置"));
            inventory.setItem(49, GuiItems.item(Material.LIME_DYE, "&a新建宝箱", "&7点击后通过聊天输入 ID 与名称"));
            inventory.setItem(51, GuiItems.item(Material.BOOK, "&b导出", "&7导出奖池及宝箱配置"));
            inventory.setItem(53, GuiItems.item(Material.ARROW, "&e下一页"));
            player.openInventory(inventory);
        });
    }

    public void openAdminCrate(Player player, String crateId, int page) {
        if (!requirePermission(player, ADMIN_PERMISSION)) {
            return;
        }
        showLoading(player, Screen.ADMIN_CRATE, crateId, page);
        await(player, () -> facade.getCrate(crateId, player.getUniqueId()), optional -> {
            if (optional.isEmpty()) {
                player.sendMessage(GuiItems.color("&c宝箱不存在：" + crateId));
                openAdminList(player, 0);
                return;
            }
            renderAdminCrate(player, optional.get(), page);
        });
    }

    public void claimPending(Player player) {
        if (!requirePermission(player, USE_PERMISSION)) {
            return;
        }
        player.closeInventory();
        awaitResult(player, () -> facade.claimPending(player), null);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ScreenHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.viewer().equals(player.getUniqueId())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        int slot = event.getRawSlot();
        switch (holder.screen()) {
            case PLAYER_LIST -> clickPlayerList(player, holder, slot);
            case PLAYER_CRATE -> clickPlayerCrate(player, holder, slot);
            case ADMIN_LIST -> clickAdminList(player, holder, slot);
            case ADMIN_CRATE -> clickAdminCrate(player, holder, slot, event.isLeftClick(), event.isRightClick(), event.isShiftClick());
            case REWARD_EDIT -> clickRewardEdit(player, holder, slot, event.isLeftClick(), event.isRightClick());
            case SCENE -> clickScene(player, holder, slot);
        }
    }

    private void renderPlayerCrate(Player player, CrateView crate, int page) {
        CrateSettings settings = crate.settings();
        ScreenHolder holder = screen(player, Screen.PLAYER_CRATE, settings.id(), null, page, 54,
                "&8" + settings.displayName());
        Inventory inventory = holder.getInventory();
        GuiItems.fill(inventory);
        inventory.setItem(4, crateIcon(crate, false));
        fillRewards(holder, crate.rewards(), page, false);
        inventory.setItem(0, GuiItems.item(Material.TRIPWIRE_HOOK, "&e钥匙与余额",
                "&7实体钥匙: &f" + crate.physicalKeys(),
                "&7虚拟钥匙: &f" + crate.playerStats().virtualKeys(),
                "&7经济: " + (crate.economyAvailable() ? "&a可用" : "&c未接入"),
                "&7余额: &f" + money(crate.economyBalance())));
        inventory.setItem(8, GuiItems.item(Material.NETHER_STAR, "&d抽取记录与保底",
                "&7累计抽取: &f" + crate.playerStats().totalDraws(),
                "&7A级保底: &f" + crate.playerStats().pityA() + "&7/&f" + settings.pityA(),
                "&7S级保底: &f" + crate.playerStats().pityS() + "&7/&f" + settings.pityS(),
                "&7待领取: &f" + crate.playerStats().pendingRewards()));
        inventory.setItem(45, GuiItems.item(Material.OAK_DOOR, "&e返回宝箱列表"));
        inventory.setItem(46, GuiItems.item(Material.ARROW, "&e上一页"));
        inventory.setItem(48, GuiItems.item(Material.LIME_DYE, "&a单抽",
                "&7消耗: &f1 把钥匙 + " + money(settings.singlePrice()),
                settings.skipAllowed() ? "&7可按服务端跳过规则跳过演出" : "&7该宝箱不可跳过演出"));
        inventory.setItem(49, GuiItems.item(Material.CHEST_MINECART, "&a领取待发奖励",
                "&7当前待领取: &f" + crate.playerStats().pendingRewards()));
        inventory.setItem(50, GuiItems.item(Material.DIAMOND, "&b七连抽",
                "&7消耗: &f7 把钥匙 + 套餐价 " + money(settings.sevenPrice()),
                "&7一次开箱动画，7 个展示位同时出现"));
        inventory.setItem(52, GuiItems.item(Material.ARROW, "&e下一页"));
        inventory.setItem(53, GuiItems.item(Material.BARRIER, "&c关闭"));
        player.openInventory(inventory);
    }

    private void renderAdminCrate(Player player, CrateView crate, int page) {
        CrateSettings settings = crate.settings();
        ScreenHolder holder = screen(player, Screen.ADMIN_CRATE, settings.id(), null, page, 54,
                "&4管理: " + settings.displayName());
        Inventory inventory = holder.getInventory();
        GuiItems.fill(inventory);
        fillRewards(holder, crate.rewards(), page, true);
        inventory.setItem(0, crateIcon(crate, true));
        inventory.setItem(1, GuiItems.item(settings.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                settings.enabled() ? "&a已启用" : "&c已停用", "&7点击切换"));
        inventory.setItem(2, GuiItems.item(Material.NAME_TAG, "&e显示名", "&f" + settings.displayName(), "&7点击聊天输入"));
        inventory.setItem(3, GuiItems.decorate(settings.keyTemplate(), "&e实体钥匙模板",
                List.of("&7点击：使用主手物品作为模板")));
        inventory.setItem(4, GuiItems.item(Material.GOLD_INGOT, "&e价格",
                "&7左键改单抽: &f" + money(settings.singlePrice()),
                "&7右键改七连: &f" + money(settings.sevenPrice())));
        inventory.setItem(5, GuiItems.item(Material.TOTEM_OF_UNDYING, "&e保底阈值",
                "&7左键改 A: &f" + settings.pityA(),
                "&7右键改 S: &f" + settings.pityS()));
        inventory.setItem(6, GuiItems.item(Material.ARMOR_STAND, "&e模型与动画",
                "&7宝箱模型: &f" + settings.crateModel(),
                "&7C: &f" + lootModels.modelFor(Rarity.C),
                "&7B: &b" + lootModels.modelFor(Rarity.B),
                "&7A: &d" + lootModels.modelFor(Rarity.A),
                "&7S: &6" + lootModels.modelFor(Rarity.S),
                "&7待机动画: &f" + settings.idleAnimation(),
                "&7开箱动画: &f" + settings.openAnimation(),
                "&7点击修改宝箱模型与动画",
                "&8Loot 配色在 config.yml 修改"));
        inventory.setItem(7, GuiItems.item(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "&e交互碰撞箱",
                "&7宽: &f" + settings.interactionWidth(),
                "&7高: &f" + settings.interactionHeight(),
                "&7点击输入 宽 高"));
        inventory.setItem(8, GuiItems.item(Material.ENDER_EYE, "&e场景坐标", "&7设置宝箱、相机与 loot1..loot7"));
        inventory.setItem(36, GuiItems.item(Material.ARROW, "&e上一页"));
        inventory.setItem(37, GuiItems.item(Material.ARROW, "&e下一页"));
        inventory.setItem(38, GuiItems.item(Material.OAK_DOOR, "&e返回管理列表"));
        inventory.setItem(39, GuiItems.item(Material.LIME_DYE, "&a添加奖励",
                "&7主手有物品：创建物品奖励",
                "&7主手为空：继续输入控制台命令"));
        inventory.setItem(40, GuiItems.item(settings.skipAllowed() ? Material.FEATHER : Material.ANVIL,
                "&e允许跳过: " + yesNo(settings.skipAllowed()), "&7点击切换"));
        inventory.setItem(41, GuiItems.item(Material.NOTE_BLOCK, "&eS级广播: " + yesNo(settings.broadcastS()), "&7点击切换"));
        inventory.setItem(42, GuiItems.item(Material.ENDER_CHEST, "&e模型放置",
                "&7左键：在当前位置放置/更新",
                "&7右键：移除当前放置"));
        inventory.setItem(43, GuiItems.item(Material.BOOK, "&b导出全部配置"));
        inventory.setItem(44, GuiItems.item(Material.WRITABLE_BOOK, "&e导入全部配置"));
        inventory.setItem(49, GuiItems.item(Material.SUNFLOWER, "&e刷新"));
        inventory.setItem(53, GuiItems.item(Material.TNT, "&c删除宝箱", "&7点击后输入 DELETE 确认"));
        player.openInventory(inventory);
    }

    private void renderRewardEditor(Player player, String crateId, RewardSettings reward) {
        ScreenHolder holder = screen(player, Screen.REWARD_EDIT, crateId, reward.id(), 0, 45,
                "&4奖励: " + reward.displayName());
        Inventory inventory = holder.getInventory();
        GuiItems.fill(inventory);
        inventory.setItem(4, rewardIcon(new RewardView(reward, 0.0D), true));
        inventory.setItem(10, GuiItems.decorate(reward.itemReward(), "&e物品奖励",
                List.of("&7左键：设为主手物品", "&7右键：移除物品（必须保留命令）")));
        inventory.setItem(12, GuiItems.item(Material.NAME_TAG, "&e名称", "&f" + reward.displayName(), "&7点击聊天输入"));
        inventory.setItem(14, GuiItems.item(Material.COMPARATOR, "&e权重", "&f" + reward.weight(), "&7点击聊天输入正数"));
        inventory.setItem(16, GuiItems.item(Material.NETHER_STAR, "&e稀有度", "&f" + reward.rarity(), "&7点击循环切换"));
        List<String> commandLore = new ArrayList<>();
        commandLore.add("&7点击输入；多条命令用 ; 分隔");
        commandLore.add("&7使用 %player% 作为玩家占位符");
        if (reward.consoleCommands().isEmpty()) {
            commandLore.add("&8（无命令）");
        } else {
            reward.consoleCommands().stream().limit(5).forEach(command -> commandLore.add("&f/" + command));
        }
        inventory.setItem(20, GuiItems.decorate(new ItemStack(Material.COMMAND_BLOCK), "&e控制台命令", commandLore));
        inventory.setItem(22, GuiItems.item(reward.broadcast() ? Material.BELL : Material.GRAY_DYE,
                "&e独立广播: " + yesNo(reward.broadcast()), "&7点击切换"));
        inventory.setItem(27, GuiItems.item(Material.OAK_DOOR, "&e返回奖池"));
        inventory.setItem(35, GuiItems.item(Material.TNT, "&c删除奖励", "&7点击后输入 DELETE 确认"));
        player.openInventory(inventory);
    }

    private void renderScene(Player player, String crateId) {
        ScreenHolder holder = screen(player, Screen.SCENE, crateId, null, 0, 27, "&4场景点: " + crateId);
        Inventory inventory = holder.getInventory();
        GuiItems.fill(inventory);
        ScenePoint[] points = ScenePoint.values();
        for (int i = 0; i < points.length; i++) {
            ScenePoint point = points[i];
            int slot = 9 + i;
            holder.setTarget(slot, point.name());
            inventory.setItem(slot, GuiItems.item(point == ScenePoint.CRATE ? Material.CHEST
                            : point == ScenePoint.CAMERA ? Material.ENDER_EYE : Material.ITEM_FRAME,
                    "&e" + point.name(), "&7点击保存当前精确位置、朝向与世界"));
        }
        inventory.setItem(22, GuiItems.item(Material.OAK_DOOR, "&e返回"));
        player.openInventory(inventory);
    }

    private void clickPlayerList(Player player, ScreenHolder holder, int slot) {
        Optional<String> crateId = holder.target(slot);
        if (crateId.isPresent()) {
            openPlayerCrate(player, crateId.get(), 0);
            return;
        }
        switch (slot) {
            case 45 -> openPlayerList(player, Math.max(0, holder.page() - 1));
            case 49 -> player.closeInventory();
            case 50 -> claimPending(player);
            case 53 -> openPlayerList(player, holder.page() + 1);
            default -> {
            }
        }
    }

    private void clickPlayerCrate(Player player, ScreenHolder holder, int slot) {
        switch (slot) {
            case 45 -> openPlayerList(player, 0);
            case 46 -> openPlayerCrate(player, holder.crateId(), Math.max(0, holder.page() - 1));
            case 48 -> requestDraw(player, holder.crateId(), DrawType.SINGLE);
            case 49 -> claimPending(player);
            case 50 -> requestDraw(player, holder.crateId(), DrawType.SEVEN);
            case 52 -> openPlayerCrate(player, holder.crateId(), holder.page() + 1);
            case 53 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void clickAdminList(Player player, ScreenHolder holder, int slot) {
        if (!requirePermission(player, ADMIN_PERMISSION)) {
            return;
        }
        Optional<String> crateId = holder.target(slot);
        if (crateId.isPresent()) {
            openAdminCrate(player, crateId.get(), 0);
            return;
        }
        switch (slot) {
            case 45 -> openAdminList(player, Math.max(0, holder.page() - 1));
            case 47 -> confirmImport(player, () -> openAdminList(player, holder.page()));
            case 49 -> createCrateFlow(player);
            case 51 -> awaitResult(player, facade::exportConfig, () -> openAdminList(player, holder.page()));
            case 53 -> openAdminList(player, holder.page() + 1);
            default -> {
            }
        }
    }

    private void clickAdminCrate(Player player, ScreenHolder holder, int slot,
                                 boolean leftClick, boolean rightClick, boolean shiftClick) {
        if (!requirePermission(player, ADMIN_PERMISSION)) {
            return;
        }
        String crateId = holder.crateId();
        Optional<String> rewardId = holder.target(slot);
        if (rewardId.isPresent()) {
            openRewardEditor(player, crateId, rewardId.get());
            return;
        }
        switch (slot) {
            case 1 -> mutateCrate(player, crateId, settings -> withEnabled(settings, !settings.enabled()));
            case 2 -> input(player, "输入新的宝箱显示名", value ->
                    mutateCrate(player, crateId, settings -> withDisplayName(settings, value)),
                    () -> openAdminCrate(player, crateId, holder.page()));
            case 3 -> setKeyTemplate(player, crateId, holder.page());
            case 4 -> inputPrice(player, crateId, holder.page(), rightClick);
            case 5 -> inputPity(player, crateId, holder.page(), rightClick);
            case 6 -> inputModels(player, crateId, holder.page());
            case 7 -> inputDimensions(player, crateId, holder.page());
            case 8 -> renderScene(player, crateId);
            case 36 -> openAdminCrate(player, crateId, Math.max(0, holder.page() - 1));
            case 37 -> openAdminCrate(player, crateId, holder.page() + 1);
            case 38 -> openAdminList(player, 0);
            case 39 -> addRewardFlow(player, crateId, holder.page());
            case 40 -> mutateCrate(player, crateId, settings -> withSkip(settings, !settings.skipAllowed()));
            case 41 -> mutateCrate(player, crateId, settings -> withBroadcast(settings, !settings.broadcastS()));
            case 42 -> {
                if (rightClick || shiftClick) {
                    awaitResult(player, () -> facade.removePlacement(crateId), () -> openAdminCrate(player, crateId, holder.page()));
                } else if (leftClick) {
                    awaitResult(player, () -> facade.placeModelCrate(crateId, player.getLocation().clone()),
                            () -> openAdminCrate(player, crateId, holder.page()));
                }
            }
            case 43 -> awaitResult(player, facade::exportConfig, () -> openAdminCrate(player, crateId, holder.page()));
            case 44 -> confirmImport(player, () -> openAdminCrate(player, crateId, holder.page()));
            case 49 -> openAdminCrate(player, crateId, holder.page());
            case 53 -> confirmDeleteCrate(player, crateId);
            default -> {
            }
        }
    }

    private void clickRewardEdit(Player player, ScreenHolder holder, int slot, boolean leftClick, boolean rightClick) {
        if (!requirePermission(player, ADMIN_PERMISSION)) {
            return;
        }
        String crateId = holder.crateId();
        String rewardId = holder.rewardId();
        switch (slot) {
            case 10 -> {
                if (leftClick) {
                    ItemStack hand = usableMainHand(player);
                    if (hand == null) {
                        player.sendMessage(GuiItems.color("&c请先把奖励物品拿在主手。"));
                    } else {
                        mutateReward(player, crateId, rewardId, reward -> withRewardItem(reward, hand, hand));
                    }
                } else if (rightClick) {
                    mutateReward(player, crateId, rewardId, reward -> {
                        if (reward.consoleCommands().isEmpty()) {
                            throw new IllegalArgumentException("至少保留物品或一条命令");
                        }
                        return withRewardItem(reward, reward.icon(), null);
                    });
                }
            }
            case 12 -> input(player, "输入奖励显示名", value ->
                    mutateReward(player, crateId, rewardId, reward -> withRewardName(reward, value)),
                    () -> openRewardEditor(player, crateId, rewardId));
            case 14 -> input(player, "输入大于 0 的权重", value -> {
                try {
                    double weight = positiveDouble(value, "权重");
                    mutateReward(player, crateId, rewardId, reward -> withRewardWeight(reward, weight));
                } catch (IllegalArgumentException exception) {
                    inputError(player, exception, () -> openRewardEditor(player, crateId, rewardId));
                }
            }, () -> openRewardEditor(player, crateId, rewardId));
            case 16 -> mutateReward(player, crateId, rewardId, reward -> withRewardRarity(reward, nextRarity(reward.rarity())));
            case 20 -> input(player, "输入控制台命令，多条用 ; 分隔；输入 none 清空", value ->
                    updateRewardCommands(player, crateId, rewardId, value),
                    () -> openRewardEditor(player, crateId, rewardId));
            case 22 -> mutateReward(player, crateId, rewardId, reward -> withRewardBroadcast(reward, !reward.broadcast()));
            case 27 -> openAdminCrate(player, crateId, 0);
            case 35 -> confirmDeleteReward(player, crateId, rewardId);
            default -> {
            }
        }
    }

    private void clickScene(Player player, ScreenHolder holder, int slot) {
        if (slot == 22) {
            openAdminCrate(player, holder.crateId(), 0);
            return;
        }
        holder.target(slot).ifPresent(value -> {
            ScenePoint point = ScenePoint.valueOf(value);
            awaitResult(player,
                    () -> facade.setScenePoint(holder.crateId(), point, player.getLocation().clone()),
                    () -> renderScene(player, holder.crateId()));
        });
    }

    private void requestDraw(Player player, String crateId, DrawType drawType) {
        player.closeInventory();
        awaitResult(player, () -> facade.requestDraw(player, crateId, drawType), () -> {
            // Successful draws own the player's view until the scene service restores it.
        }, () -> openPlayerCrate(player, crateId, 0));
    }

    private void createCrateFlow(Player player) {
        input(player, "输入新宝箱 ID（小写字母、数字、_、-，最多 32 位）", idValue -> {
            String id = idValue.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                player.sendMessage(GuiItems.color("&cID 格式不正确。"));
                openAdminList(player, 0);
                return;
            }
            input(player, "输入宝箱显示名", displayName ->
                    awaitResult(player, () -> facade.createCrate(id, displayName), () -> openAdminCrate(player, id, 0)),
                    () -> openAdminList(player, 0));
        }, () -> openAdminList(player, 0));
    }

    private void addRewardFlow(Player player, String crateId, int page) {
        ItemStack held = usableMainHand(player);
        input(player, "输入奖励 ID（小写字母、数字、_、-，最多 32 位）", idValue -> {
            String id = idValue.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                player.sendMessage(GuiItems.color("&c奖励 ID 格式不正确。"));
                openAdminCrate(player, crateId, page);
                return;
            }
            input(player, "输入奖励显示名", displayName -> {
                if (held != null) {
                    RewardSettings reward = new RewardSettings(id, displayName, held, held, List.of(), 1.0D, Rarity.C, false);
                    saveReward(player, crateId, reward, () -> openRewardEditor(player, crateId, id));
                    return;
                }
                input(player, "主手为空：请输入控制台命令，多条用 ; 分隔", commands -> {
                    List<String> parsed = commands(commands);
                    if (parsed.isEmpty()) {
                        player.sendMessage(GuiItems.color("&c命令不能为空。"));
                        openAdminCrate(player, crateId, page);
                        return;
                    }
                    RewardSettings reward = new RewardSettings(id, displayName, new ItemStack(Material.COMMAND_BLOCK),
                            null, parsed, 1.0D, Rarity.C, false);
                    saveReward(player, crateId, reward, () -> openRewardEditor(player, crateId, id));
                }, () -> openAdminCrate(player, crateId, page));
            }, () -> openAdminCrate(player, crateId, page));
        }, () -> openAdminCrate(player, crateId, page));
    }

    private void setKeyTemplate(Player player, String crateId, int page) {
        ItemStack hand = usableMainHand(player);
        if (hand == null) {
            player.sendMessage(GuiItems.color("&c请先把钥匙模板物品拿在主手。"));
            return;
        }
        mutateCrate(player, crateId, settings -> withKeyTemplate(settings, hand));
    }

    private void inputPrice(Player player, String crateId, int page, boolean seven) {
        input(player, seven ? "输入七连套餐金币价格（>= 0）" : "输入单抽金币价格（>= 0）", value -> {
            try {
                double amount = nonNegativeDouble(value, "价格");
                mutateCrate(player, crateId, settings -> seven
                        ? withPrices(settings, settings.singlePrice(), amount)
                        : withPrices(settings, amount, settings.sevenPrice()));
            } catch (IllegalArgumentException exception) {
                inputError(player, exception, () -> openAdminCrate(player, crateId, page));
            }
        }, () -> openAdminCrate(player, crateId, page));
    }

    private void inputPity(Player player, String crateId, int page, boolean sPity) {
        input(player, sPity ? "输入 S 级保底次数（正整数，默认 80）" : "输入 A 级保底次数（正整数，默认 10）", value -> {
            try {
                int amount = positiveInt(value, "保底次数");
                mutateCrate(player, crateId, settings -> sPity
                        ? withPity(settings, settings.pityA(), amount)
                        : withPity(settings, amount, settings.pityS()));
            } catch (IllegalArgumentException exception) {
                inputError(player, exception, () -> openAdminCrate(player, crateId, page));
            }
        }, () -> openAdminCrate(player, crateId, page));
    }

    private void inputModels(Player player, String crateId, int page) {
        input(player, "依次输入 宝箱模型|待机动画|开箱动画", value -> {
            String[] parts = Arrays.stream(value.split("\\|", -1)).map(String::trim).toArray(String[]::new);
            if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
                player.sendMessage(GuiItems.color("&c必须提供三个非空值，并用 | 分隔。"));
                openAdminCrate(player, crateId, page);
                return;
            }
            mutateCrate(player, crateId, settings -> withModels(settings, parts[0], parts[1], parts[2]));
        }, () -> openAdminCrate(player, crateId, page));
    }

    private void inputDimensions(Player player, String crateId, int page) {
        input(player, "输入交互碰撞箱宽和高，例如：1.5 2.0", value -> {
            String[] parts = value.trim().split("\\s+");
            try {
                if (parts.length != 2) {
                    throw new IllegalArgumentException("请输入两个数字：宽 高");
                }
                double width = positiveDouble(parts[0], "宽度");
                double height = positiveDouble(parts[1], "高度");
                mutateCrate(player, crateId, settings -> withDimensions(settings, width, height));
            } catch (IllegalArgumentException exception) {
                inputError(player, exception, () -> openAdminCrate(player, crateId, page));
            }
        }, () -> openAdminCrate(player, crateId, page));
    }

    private void openRewardEditor(Player player, String crateId, String rewardId) {
        showLoading(player, Screen.REWARD_EDIT, crateId, 0);
        await(player, () -> facade.getCrate(crateId, player.getUniqueId()), optional -> {
            Optional<RewardSettings> reward = optional.stream()
                    .flatMap(crate -> crate.rewards().stream())
                    .map(RewardView::settings)
                    .filter(settings -> settings.id().equalsIgnoreCase(rewardId))
                    .findFirst();
            if (reward.isEmpty()) {
                player.sendMessage(GuiItems.color("&c奖励不存在：" + rewardId));
                openAdminCrate(player, crateId, 0);
                return;
            }
            renderRewardEditor(player, crateId, reward.get());
        });
    }

    private void mutateCrate(Player player, String crateId, UnaryOperator<CrateSettings> operation) {
        showLoading(player, Screen.ADMIN_CRATE, crateId, 0);
        await(player, () -> facade.getCrate(crateId, player.getUniqueId()), optional -> {
            if (optional.isEmpty()) {
                player.sendMessage(GuiItems.color("&c宝箱不存在：" + crateId));
                openAdminList(player, 0);
                return;
            }
            try {
                CrateSettings updated = operation.apply(optional.get().settings());
                awaitResult(player, () -> facade.saveCrate(updated), () -> openAdminCrate(player, crateId, 0));
            } catch (RuntimeException exception) {
                inputError(player, exception, () -> openAdminCrate(player, crateId, 0));
            }
        });
    }

    private void mutateReward(Player player, String crateId, String rewardId, UnaryOperator<RewardSettings> operation) {
        showLoading(player, Screen.REWARD_EDIT, crateId, 0);
        await(player, () -> facade.getCrate(crateId, player.getUniqueId()), optional -> {
            Optional<RewardSettings> found = optional.stream()
                    .flatMap(crate -> crate.rewards().stream())
                    .map(RewardView::settings)
                    .filter(reward -> reward.id().equalsIgnoreCase(rewardId))
                    .findFirst();
            if (found.isEmpty()) {
                player.sendMessage(GuiItems.color("&c奖励不存在：" + rewardId));
                openAdminCrate(player, crateId, 0);
                return;
            }
            try {
                saveReward(player, crateId, operation.apply(found.get()), () -> openRewardEditor(player, crateId, rewardId));
            } catch (RuntimeException exception) {
                inputError(player, exception, () -> openRewardEditor(player, crateId, rewardId));
            }
        });
    }

    private void saveReward(Player player, String crateId, RewardSettings reward, Runnable after) {
        awaitResult(player, () -> facade.saveReward(crateId, reward), after);
    }

    private void updateRewardCommands(Player player, String crateId, String rewardId, String raw) {
        mutateReward(player, crateId, rewardId, reward -> {
            List<String> parsed = raw.equalsIgnoreCase("none") ? List.of() : commands(raw);
            if (parsed.isEmpty() && reward.itemReward() == null) {
                throw new IllegalArgumentException("至少保留物品或一条命令");
            }
            return withRewardCommands(reward, parsed);
        });
    }

    private void confirmDeleteCrate(Player player, String crateId) {
        input(player, "删除不可撤销；输入 DELETE 确认删除宝箱 " + crateId, value -> {
            if (!value.equals("DELETE")) {
                player.sendMessage(GuiItems.color("&7内容不匹配，已取消删除。"));
                openAdminCrate(player, crateId, 0);
                return;
            }
            awaitResult(player, () -> facade.deleteCrate(crateId), () -> openAdminList(player, 0));
        }, () -> openAdminCrate(player, crateId, 0));
    }

    private void confirmDeleteReward(Player player, String crateId, String rewardId) {
        input(player, "删除不可撤销；输入 DELETE 确认删除奖励 " + rewardId, value -> {
            if (!value.equals("DELETE")) {
                player.sendMessage(GuiItems.color("&7内容不匹配，已取消删除。"));
                openRewardEditor(player, crateId, rewardId);
                return;
            }
            awaitResult(player, () -> facade.deleteReward(crateId, rewardId), () -> openAdminCrate(player, crateId, 0));
        }, () -> openRewardEditor(player, crateId, rewardId));
    }

    private void confirmImport(Player player, Runnable after) {
        input(player, "导入可能覆盖同 ID 配置；输入 IMPORT 确认", value -> {
            if (!value.equals("IMPORT")) {
                player.sendMessage(GuiItems.color("&7内容不匹配，已取消导入。"));
                after.run();
                return;
            }
            awaitResult(player, facade::importConfig, after);
        }, after);
    }

    private void fillCrateList(ScreenHolder holder, List<CrateView> crates, int page, boolean admin) {
        int start = Math.max(0, page) * LIST_PAGE_SIZE;
        for (int slot = 0; slot < LIST_PAGE_SIZE && start + slot < crates.size(); slot++) {
            CrateView crate = crates.get(start + slot);
            holder.setTarget(slot, crate.settings().id());
            holder.getInventory().setItem(slot, crateIcon(crate, admin));
        }
    }

    private void fillRewards(ScreenHolder holder, List<RewardView> rewards, int page, boolean admin) {
        int start = Math.max(0, page) * REWARD_PAGE_SIZE;
        for (int index = 0; index < REWARD_PAGE_SIZE && start + index < rewards.size(); index++) {
            int slot = 9 + index;
            RewardView reward = rewards.get(start + index);
            if (admin) {
                holder.setTarget(slot, reward.settings().id());
            }
            holder.getInventory().setItem(slot, rewardIcon(reward, admin));
        }
    }

    private ItemStack crateIcon(CrateView crate, boolean admin) {
        CrateSettings settings = crate.settings();
        ItemStack icon = settings.icon() == null ? new ItemStack(Material.CHEST) : settings.icon();
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + settings.id());
        lore.add("&7状态: " + (settings.enabled() ? "&a启用" : "&c停用"));
        lore.add("&7奖励数量: &f" + crate.rewards().size());
        lore.add("&7单抽: &f1 钥匙 + " + money(settings.singlePrice()));
        lore.add("&7七连: &f7 钥匙 + " + money(settings.sevenPrice()));
        lore.add("&7A/S 保底: &f" + settings.pityA() + " / " + settings.pityS());
        lore.add(admin ? "&e点击管理" : "&e点击查看奖池与概率");
        return GuiItems.decorate(icon, (settings.enabled() ? "&a" : "&c") + settings.displayName(), lore);
    }

    private ItemStack rewardIcon(RewardView reward, boolean admin) {
        RewardSettings settings = reward.settings();
        ItemStack icon = settings.icon();
        if (icon == null) {
            icon = settings.itemReward();
        }
        if (icon == null) {
            icon = new ItemStack(Material.COMMAND_BLOCK);
        }
        List<String> lore = new ArrayList<>();
        lore.add("&7ID: &f" + settings.id());
        lore.add("&7稀有度: &f" + settings.rarity());
        lore.add("&7权重: &f" + settings.weight());
        lore.add("&7概率: &f" + String.format(Locale.ROOT, "%.4f%%", reward.probability() * 100.0D));
        lore.add("&7物品: " + (settings.itemReward() == null ? "&c无" : "&a有"));
        lore.add("&7命令数: &f" + settings.consoleCommands().size());
        lore.add("&7广播: " + yesNo(settings.broadcast()));
        if (admin) {
            lore.add("&e点击编辑");
        }
        return GuiItems.decorate(icon, rarityColor(settings.rarity()) + settings.displayName(), lore);
    }

    private ScreenHolder screen(Player player, Screen screen, String crateId, String rewardId,
                                int page, int size, String title) {
        return new ScreenHolder(player.getUniqueId(), screen, crateId, rewardId, Math.max(0, page), size,
                GuiItems.color(title));
    }

    private void showLoading(Player player, Screen screen, String crateId, int page) {
        ScreenHolder holder = screen(player, screen, crateId, null, page, 27, "&8正在读取...");
        GuiItems.fill(holder.getInventory());
        holder.getInventory().setItem(13, GuiItems.item(Material.CLOCK, "&e正在读取数据...", "&7SQLite 操作不会阻塞服务器主线程"));
        player.openInventory(holder.getInventory());
    }

    private void input(Player player, String prompt, Consumer<String> accepted, Runnable cancelled) {
        chatInputs.request(player, prompt, accepted, cancelled);
    }

    private <T> void await(Player player, Supplier<CompletableFuture<T>> operation, Consumer<T> success) {
        await(player, operation, success, () -> {
        });
    }

    private <T> void await(Player player, Supplier<CompletableFuture<T>> operation,
                           Consumer<T> success, Runnable failure) {
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(operation.get(), "Facade returned a null future");
        } catch (Throwable throwable) {
            reportFailure(player, throwable);
            failure.run();
            return;
        }
        future.whenComplete((value, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (throwable != null) {
                reportFailure(player, throwable);
                failure.run();
                return;
            }
            try {
                success.accept(value);
            } catch (Throwable callbackFailure) {
                reportFailure(player, callbackFailure);
            }
        }));
    }

    private void awaitResult(Player player, Supplier<CompletableFuture<GuiResult>> operation, Runnable after) {
        awaitResult(player, operation, after, after);
    }

    private void awaitResult(Player player, Supplier<CompletableFuture<GuiResult>> operation,
                             Runnable success, Runnable failure) {
        await(player, operation, result -> {
            String prefix = result.success() ? "&a" : "&c";
            if (!result.message().isBlank()) {
                player.sendMessage(GuiItems.color(prefix + result.message()));
            }
            (result.success() ? success : failure).run();
        }, failure);
    }

    private void reportFailure(Player player, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        player.sendMessage(GuiItems.color("&c操作失败：" + message));
        plugin.getLogger().warning("GUI operation failed for " + player.getName() + ": " + message);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getClass().getName().equals("java.util.concurrent.ExecutionException"))
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private boolean requirePermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        player.sendMessage(GuiItems.color("&c你没有权限：" + permission));
        return false;
    }

    private void inputError(Player player, RuntimeException exception, Runnable after) {
        player.sendMessage(GuiItems.color("&c输入无效：" + exception.getMessage()));
        after.run();
    }

    private static ItemStack usableMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item.getType().isAir() ? null : item.clone();
    }

    private static List<String> commands(String raw) {
        return Arrays.stream(raw.split(";"))
                .map(String::trim)
                .map(command -> command.startsWith("/") ? command.substring(1) : command)
                .filter(command -> !command.isBlank())
                .toList();
    }

    private static double nonNegativeDouble(String raw, String name) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value) || value < 0.0D) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + "必须是 >= 0 的数字");
        }
    }

    private static double positiveDouble(String raw, String name) {
        double value = nonNegativeDouble(raw, name);
        if (value <= 0.0D) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
        return value;
    }

    private static int positiveInt(String raw, String name) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + "必须是正整数");
        }
    }

    private static String money(double amount) {
        return String.format(Locale.ROOT, "%.2f 金币", amount);
    }

    private static String yesNo(boolean value) {
        return value ? "&a是" : "&c否";
    }

    private static String rarityColor(Rarity rarity) {
        return switch (rarity) {
            case S -> "&6";
            case A -> "&d";
            case B -> "&b";
            case C -> "&7";
        };
    }

    private static Rarity nextRarity(Rarity rarity) {
        Rarity[] values = Rarity.values();
        return values[(rarity.ordinal() + 1) % values.length];
    }

    private static CrateSettings withEnabled(CrateSettings s, boolean value) {
        return new CrateSettings(s.id(), s.displayName(), value, s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withDisplayName(CrateSettings s, String value) {
        return new CrateSettings(s.id(), value, s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withKeyTemplate(CrateSettings s, ItemStack value) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), value,
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withPrices(CrateSettings s, double single, double seven) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                single, seven, s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withPity(CrateSettings s, int pityA, int pityS) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), pityA, pityS, s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withBroadcast(CrateSettings s, boolean value) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), value, s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withSkip(CrateSettings s, boolean value) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), value,
                s.interactionWidth(), s.interactionHeight(), s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withDimensions(CrateSettings s, double width, double height) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                width, height, s.crateModel(), s.lootModel(), s.idleAnimation(), s.openAnimation());
    }

    private static CrateSettings withModels(CrateSettings s, String crateModel,
                                            String idleAnimation, String openAnimation) {
        return new CrateSettings(s.id(), s.displayName(), s.enabled(), s.icon(), s.keyTemplate(),
                s.singlePrice(), s.sevenPrice(), s.pityA(), s.pityS(), s.broadcastS(), s.skipAllowed(),
                s.interactionWidth(), s.interactionHeight(), crateModel, s.lootModel(), idleAnimation, openAnimation);
    }

    private static RewardSettings withRewardItem(RewardSettings r, ItemStack icon, ItemStack item) {
        return new RewardSettings(r.id(), r.displayName(), icon, item, r.consoleCommands(),
                r.weight(), r.rarity(), r.broadcast());
    }

    private static RewardSettings withRewardName(RewardSettings r, String value) {
        return new RewardSettings(r.id(), value, r.icon(), r.itemReward(), r.consoleCommands(),
                r.weight(), r.rarity(), r.broadcast());
    }

    private static RewardSettings withRewardWeight(RewardSettings r, double value) {
        return new RewardSettings(r.id(), r.displayName(), r.icon(), r.itemReward(), r.consoleCommands(),
                value, r.rarity(), r.broadcast());
    }

    private static RewardSettings withRewardRarity(RewardSettings r, Rarity value) {
        return new RewardSettings(r.id(), r.displayName(), r.icon(), r.itemReward(), r.consoleCommands(),
                r.weight(), value, r.broadcast());
    }

    private static RewardSettings withRewardCommands(RewardSettings r, List<String> value) {
        return new RewardSettings(r.id(), r.displayName(), r.icon(), r.itemReward(), value,
                r.weight(), r.rarity(), r.broadcast());
    }

    private static RewardSettings withRewardBroadcast(RewardSettings r, boolean value) {
        return new RewardSettings(r.id(), r.displayName(), r.icon(), r.itemReward(), r.consoleCommands(),
                r.weight(), r.rarity(), value);
    }
}
