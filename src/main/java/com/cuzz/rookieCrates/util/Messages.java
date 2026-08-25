package com.cuzz.rookieCrates.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;

public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Messages(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, String> placeholders) {
        FileConfiguration config = plugin.getConfig();
        String prefix = config.getString("messages.prefix", "");
        String value = config.getString("messages." + key, key);
        String rendered = prefix + value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace("<" + entry.getKey() + ">", escape(entry.getValue()));
        }
        return miniMessage.deserialize(rendered);
    }

    public void send(CommandSender receiver, String key) {
        receiver.sendMessage(component(key));
    }

    public void send(CommandSender receiver, String key, Map<String, String> placeholders) {
        receiver.sendMessage(component(key, placeholders));
    }

    public Component parse(String miniMessageText, Map<String, String> placeholders) {
        String rendered = Objects.requireNonNullElse(miniMessageText, "");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace("<" + entry.getKey() + ">", escape(entry.getValue()));
        }
        return miniMessage.deserialize(rendered);
    }

    private String escape(String value) {
        return miniMessage.escapeTags(Objects.requireNonNullElse(value, ""));
    }
}
