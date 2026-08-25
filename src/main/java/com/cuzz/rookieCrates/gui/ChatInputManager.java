package com.cuzz.rookieCrates.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One-shot chat input with a hard 60 second timeout and /cancel support. */
public final class ChatInputManager implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ChatInputManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void request(Player player, String prompt, Consumer<String> accepted, Runnable cancelled) {
        cancel(player.getUniqueId(), false);
        player.closeInventory();
        player.sendMessage(GuiItems.color("&e" + prompt));
        player.sendMessage(GuiItems.color("&7请在聊天栏输入，输入 &c/cancel &7取消；60 秒后自动超时。"));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session removed = sessions.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(GuiItems.color("&c输入已超时。"));
                removed.cancelled().run();
            }
        }, 20L * 60L);
        sessions.put(player.getUniqueId(), new Session(accepted, cancelled, timeout));
    }

    public boolean hasSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public void cancel(UUID playerId, boolean invokeCallback) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        session.timeout().cancel();
        if (invokeCallback) {
            session.cancelled().run();
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!hasSession(playerId)) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> accept(event.getPlayer(), message));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/cancel") || !hasSession(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        accept(event.getPlayer(), "/cancel");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    private void accept(Player player, String message) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.timeout().cancel();
        if (message.equalsIgnoreCase("/cancel") || message.equalsIgnoreCase("cancel")) {
            player.sendMessage(GuiItems.color("&7输入已取消。"));
            session.cancelled().run();
            return;
        }
        session.accepted().accept(message);
    }

    private record Session(Consumer<String> accepted, Runnable cancelled, BukkitTask timeout) {
        private Session {
            Objects.requireNonNull(accepted, "accepted");
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(timeout, "timeout");
        }
    }
}
