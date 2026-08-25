package com.cuzz.rookieCrates.service;

import com.cuzz.rookieCrates.economy.EconomyGateway;
import com.cuzz.rookieCrates.key.PhysicalKeyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;

/** Coordinates the non-SQLite part of an opening payment on the server thread. */
public final class PaymentService {

    private final PhysicalKeyService physicalKeys;
    private final EconomyGateway economy;

    public PaymentService(PhysicalKeyService physicalKeys, EconomyGateway economy) {
        this.physicalKeys = Objects.requireNonNull(physicalKeys, "physicalKeys");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    public Quote quote(Player player, String crateId, int requiredKeys,
                       int virtualKeyBalance, double moneyCost) {
        validate(requiredKeys, virtualKeyBalance, moneyCost);
        int virtualToUse = Math.min(requiredKeys, virtualKeyBalance);
        int physicalRequired = requiredKeys - virtualToUse;
        int physicalAvailable = physicalKeys.count(player.getInventory(), crateId);
        boolean keySatisfied = physicalAvailable >= physicalRequired;
        boolean economySatisfied = moneyCost == 0.0D
                || (economy.isAvailable() && economy.balance(player) >= moneyCost);
        return new Quote(requiredKeys, virtualToUse, physicalRequired, physicalAvailable,
                moneyCost, keySatisfied, economy.isAvailable(), economySatisfied);
    }

    /**
     * Removes physical keys and Vault money. Virtual keys are returned in the receipt
     * and must be decremented in the same SQLite transaction as the draw.
     */
    public Receipt charge(Player player, String crateId, ItemStack keyTemplate,
                          Quote quote) throws PaymentException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(keyTemplate, "keyTemplate");
        Objects.requireNonNull(quote, "quote");
        if (!quote.keySatisfied()) {
            throw new PaymentException(Reason.INSUFFICIENT_KEYS, "Not enough crate keys");
        }
        if (!quote.economyAvailable() && quote.moneyCost() > 0.0D) {
            throw new PaymentException(Reason.ECONOMY_UNAVAILABLE, "Vault economy provider is unavailable");
        }
        if (!quote.economySatisfied()) {
            throw new PaymentException(Reason.INSUFFICIENT_MONEY, "Not enough money");
        }

        if (quote.physicalKeys() > 0
                && !physicalKeys.consume(player.getInventory(), crateId, quote.physicalKeys())) {
            throw new PaymentException(Reason.INSUFFICIENT_KEYS, "Physical key inventory changed");
        }

        EconomyGateway.TransactionResult money = economy.withdraw(player, quote.moneyCost());
        if (quote.moneyCost() > 0.0D && !money.success()) {
            refundPhysical(player, crateId, keyTemplate, quote.physicalKeys());
            throw new PaymentException(Reason.WITHDRAW_FAILED, money.message());
        }
        return new Receipt(crateId, quote.virtualKeys(), quote.physicalKeys(),
                quote.moneyCost(), keyTemplate.clone());
    }

    public RefundResult refund(Player player, Receipt receipt) {
        EconomyGateway.TransactionResult money = refundMoney(player, receipt);
        refundPhysical(player, receipt.crateId(), receipt.keyTemplate(), receipt.physicalKeys());
        return new RefundResult(money.success(), money.message());
    }

    /** Refunds the Vault portion even when the player disconnected during the SQLite commit. */
    public EconomyGateway.TransactionResult refundMoney(OfflinePlayer player, Receipt receipt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.money() <= 0.0D) {
            return new EconomyGateway.TransactionResult(true, 0.0D, "no money charged");
        }
        return economy.deposit(player, receipt.money());
    }

    private void refundPhysical(Player player, String crateId, ItemStack template, int amount) {
        if (amount <= 0) {
            return;
        }
        Map<Integer, ItemStack> overflow = physicalKeys.give(player.getInventory(), template, crateId, amount);
        overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private static void validate(int requiredKeys, int virtualKeyBalance, double moneyCost) {
        if (requiredKeys <= 0 || virtualKeyBalance < 0
                || !Double.isFinite(moneyCost) || moneyCost < 0.0D) {
            throw new IllegalArgumentException("Invalid opening payment values");
        }
    }

    public record Quote(int requiredKeys, int virtualKeys, int physicalKeys,
                        int physicalAvailable, double moneyCost, boolean keySatisfied,
                        boolean economyAvailable, boolean economySatisfied) {
    }

    public record Receipt(String crateId, int virtualKeys, int physicalKeys,
                          double money, ItemStack keyTemplate) {
    }

    public record RefundResult(boolean moneyRefunded, String message) {
    }

    public enum Reason {
        INSUFFICIENT_KEYS,
        ECONOMY_UNAVAILABLE,
        INSUFFICIENT_MONEY,
        WITHDRAW_FAILED
    }

    public static final class PaymentException extends Exception {
        private final Reason reason;

        public PaymentException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
