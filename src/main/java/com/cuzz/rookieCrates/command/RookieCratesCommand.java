package com.cuzz.rookieCrates.command;

import com.cuzz.rookieCrates.gui.CratesGuiController;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.GuiResult;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade.ScenePoint;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class RookieCratesCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final CratesGuiFacade facade;
    private final CratesGuiController gui;

    public RookieCratesCommand(JavaPlugin plugin, CratesGuiFacade facade, CratesGuiController gui) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.facade = Objects.requireNonNull(facade, "facade");
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Player player = player(sender);
            if (player != null) {
                gui.openPlayerList(player, 0);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> open(sender, args);
            case "claim" -> {
                Player player = player(sender);
                if (player != null) gui.claimPending(player);
            }
            case "admin" -> {
                Player player = adminPlayer(sender);
                if (player != null) gui.openAdminList(player, 0);
            }
            case "config" -> config(sender, args);
            case "preview" -> preview(sender, args);
            case "key" -> key(sender, args);
            case "place" -> place(sender, args);
            case "remove" -> remove(sender, args);
            case "scene" -> scene(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    private void open(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        if (args.length < 2) gui.openPlayerList(player, 0);
        else gui.openPlayerCrate(player, args[1], 0);
    }

    private void config(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return;
        if (args.length != 2) {
            sender.sendMessage("§c用法: /rookiecrates config <宝箱ID>");
            return;
        }
        gui.openSceneConfig(player, args[1]);
    }

    private void preview(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return;
        if (args.length != 2) {
            sender.sendMessage("§c用法: /rookiecrates preview <宝箱ID>");
            return;
        }
        result(sender, () -> facade.previewLoot(player, args[1]));
    }

    private void key(CommandSender sender, String[] args) {
        if (!admin(sender) || args.length != 6 || !args[1].equalsIgnoreCase("give")) {
            sender.sendMessage("§c用法: /rookiecrates key give <玩家> <宝箱ID> <数量> <physical|virtual>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage("§c目标玩家必须在线。");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[4]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            sender.sendMessage("§c数量必须是正整数。");
            return;
        }
        if (args[5].equalsIgnoreCase("physical")) {
            result(sender, () -> facade.givePhysicalKeys(target, args[3], amount));
        } else if (args[5].equalsIgnoreCase("virtual")) {
            result(sender, () -> facade.addVirtualKeys(target.getUniqueId(), args[3], amount));
        } else {
            sender.sendMessage("§c类型只能是 physical 或 virtual。");
        }
    }

    private void place(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return;
        if (args.length != 2) {
            sender.sendMessage("§c用法: /rookiecrates place <宝箱ID>");
            return;
        }
        result(sender, () -> facade.placeModelCrate(args[1], player.getLocation().clone()));
    }

    private void remove(CommandSender sender, String[] args) {
        if (!admin(sender) || args.length != 2) {
            sender.sendMessage("§c用法: /rookiecrates remove <宝箱ID>");
            return;
        }
        result(sender, () -> facade.removePlacement(args[1]));
    }

    private void scene(CommandSender sender, String[] args) {
        Player player = adminPlayer(sender);
        if (player == null) return;
        if (args.length != 3) {
            sender.sendMessage("§c用法: /rookiecrates scene <宝箱ID> <CRATE|CAMERA|LOOT_1..LOOT_7>");
            return;
        }
        try {
            ScenePoint point = ScenePoint.valueOf(args[2].toUpperCase(Locale.ROOT));
            result(sender, () -> facade.setScenePoint(args[1], point, player.getLocation().clone()));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c场景点无效，可用 CRATE、CAMERA、LOOT_1..LOOT_7。");
        }
    }

    private void result(CommandSender sender, Supplier<CompletableFuture<GuiResult>> operation) {
        CompletableFuture<GuiResult> future;
        try {
            future = Objects.requireNonNull(operation.get(), "Facade returned null future");
        } catch (RuntimeException exception) {
            sender.sendMessage("§c操作失败：" + exception.getMessage());
            return;
        }
        future.whenComplete((value, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                sender.sendMessage("§c操作失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
            } else {
                sender.sendMessage((value.success() ? "§a" : "§c") + value.message());
            }
        }));
    }

    private static Player player(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return null;
        }
        if (!player.hasPermission(CratesGuiController.USE_PERMISSION)) {
            player.sendMessage("§c你没有权限：" + CratesGuiController.USE_PERMISSION);
            return null;
        }
        return player;
    }

    private static Player adminPlayer(CommandSender sender) {
        if (!admin(sender)) return null;
        if (sender instanceof Player player) return player;
        sender.sendMessage("§c该管理操作需要玩家的当前位置。");
        return null;
    }

    private static boolean admin(CommandSender sender) {
        if (sender.hasPermission(CratesGuiController.ADMIN_PERMISSION)) return true;
        sender.sendMessage("§c你没有权限：" + CratesGuiController.ADMIN_PERMISSION);
        return false;
    }

    private static void help(CommandSender sender, String label) {
        sender.sendMessage("§6RookieCrates 命令");
        sender.sendMessage("§e/" + label + " [open <宝箱ID>] §7- 打开奖池预览");
        sender.sendMessage("§e/" + label + " claim §7- 领取待发奖励");
        if (sender.hasPermission(CratesGuiController.ADMIN_PERMISSION)) {
            sender.sendMessage("§c/" + label + " admin §7- 管理 GUI");
            sender.sendMessage("§c/" + label + " config <宝箱ID> §7- 打开场景点位 GUI");
            sender.sendMessage("§c/" + label + " preview <宝箱ID> §7- 随机预览七个 Loot 展示物");
            sender.sendMessage("§c/" + label + " key give <玩家> <宝箱ID> <数量> <physical|virtual>");
            sender.sendMessage("§c/" + label + " place|remove <宝箱ID>");
            sender.sendMessage("§c/" + label + " scene <宝箱ID> <场景点>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(sender.hasPermission(CratesGuiController.ADMIN_PERMISSION)
                    ? List.of("open", "claim", "admin", "config", "preview", "key", "place", "remove", "scene", "help")
                    : List.of("open", "claim", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("key")) return filter(List.of("give"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("key") && args[1].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("key")) return filter(List.of("physical", "virtual"), args[5]);
        if (args.length == 3 && args[0].equalsIgnoreCase("scene")) {
            return filter(Arrays.stream(ScenePoint.values()).map(Enum::name).toList(), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
