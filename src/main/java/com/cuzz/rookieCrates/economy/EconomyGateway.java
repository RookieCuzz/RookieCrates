package com.cuzz.rookieCrates.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyGateway {

    boolean isAvailable();

    String format(double amount);

    double balance(OfflinePlayer player);

    TransactionResult withdraw(OfflinePlayer player, double amount);

    TransactionResult deposit(OfflinePlayer player, double amount);

    record TransactionResult(boolean success, double amount, String message) {
        public static TransactionResult unavailable(String message) {
            return new TransactionResult(false, 0.0D, message);
        }
    }
}
