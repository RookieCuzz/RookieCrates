package com.cuzz.rookieCrates.gui;

import com.cuzz.rookieCrates.command.RookieCratesCommand;
import com.cuzz.rookieCrates.gui.api.CratesGuiFacade;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class CratesGuiModule {

    private CratesGuiModule() {
    }

    /** Registers the production GUI listeners and command executor. */
    public static CratesGuiController install(JavaPlugin plugin, CratesGuiFacade facade) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(facade, "facade");
        ChatInputManager inputs = new ChatInputManager(plugin);
        CratesGuiController controller = new CratesGuiController(plugin, facade, inputs);
        plugin.getServer().getPluginManager().registerEvents(inputs, plugin);
        plugin.getServer().getPluginManager().registerEvents(controller, plugin);
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("rookiecrates"),
                "rookiecrates is missing from plugin.yml");
        RookieCratesCommand executor = new RookieCratesCommand(plugin, facade, controller);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return controller;
    }
}
