package com.cuzz.rookieCrates.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class VaultEconomyGateway implements EconomyGateway {

    private final JavaPlugin plugin;
    private volatile Economy economy;

    public VaultEconomyGateway(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        refresh();
    }

    public boolean refresh() {
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        this.economy = registration == null ? null : registration.getProvider();
        return this.economy != null;
    }

    @Override
    public boolean isAvailable() {
        return economy != null;
    }

    @Override
    public String format(double amount) {
        Economy current = economy;
        return current == null ? String.format("%.2f", amount) : current.format(amount);
    }

    @Override
    public double balance(OfflinePlayer player) {
        Economy current = economy;
        return current == null ? 0.0D : current.getBalance(player);
    }

    @Override
    public TransactionResult withdraw(OfflinePlayer player, double amount) {
        validateAmount(amount);
        Economy current = economy;
        if (current == null) {
            return TransactionResult.unavailable("Vault economy provider is unavailable");
        }
        EconomyResponse response = current.withdrawPlayer(player, amount);
        return new TransactionResult(response.transactionSuccess(), response.amount, response.errorMessage);
    }

    @Override
    public TransactionResult deposit(OfflinePlayer player, double amount) {
        validateAmount(amount);
        Economy current = economy;
        if (current == null) {
            return TransactionResult.unavailable("Vault economy provider is unavailable");
        }
        EconomyResponse response = current.depositPlayer(player, amount);
        return new TransactionResult(response.transactionSuccess(), response.amount, response.errorMessage);
    }

    private static void validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0D) {
            throw new IllegalArgumentException("Economy amount must be finite and non-negative");
        }
    }
}
