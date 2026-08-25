package com.cuzz.rookieCrates.key;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PhysicalKeyService {

    private final NamespacedKey crateKey;

    public PhysicalKeyService(JavaPlugin plugin) {
        this.crateKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "crate_key");
    }

    public ItemStack createKey(ItemStack visualTemplate, String crateId, int amount) {
        Objects.requireNonNull(visualTemplate, "visualTemplate");
        validateAmount(amount);
        if (visualTemplate.getType() == Material.AIR) {
            throw new IllegalArgumentException("Key template cannot be air");
        }
        ItemStack key = visualTemplate.clone();
        key.setAmount(Math.min(amount, key.getMaxStackSize()));
        ItemMeta meta = key.getItemMeta();
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.STRING, normalize(crateId));
        key.setItemMeta(meta);
        return key;
    }

    public boolean isKey(ItemStack stack, String crateId) {
        return crateId(stack).filter(normalize(crateId)::equals).isPresent();
    }

    /** Returns the normalized crate id carried by a tagged physical key. */
    public Optional<String> crateId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        String tagged = stack.getItemMeta().getPersistentDataContainer()
                .get(crateKey, PersistentDataType.STRING);
        if (tagged == null || tagged.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(tagged.trim().toLowerCase(Locale.ROOT));
    }

    public int count(PlayerInventory inventory, String crateId) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (isKey(stack, crateId)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Consumes exactly {@code amount}; callers must verify count first. */
    public boolean consume(PlayerInventory inventory, String crateId, int amount) {
        validateAmount(amount);
        if (count(inventory, crateId) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!isKey(stack, crateId)) {
                continue;
            }
            int remove = Math.min(stack.getAmount(), remaining);
            remaining -= remove;
            if (stack.getAmount() == remove) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - remove);
                inventory.setItem(slot, stack);
            }
        }
        return remaining == 0;
    }

    public Map<Integer, ItemStack> give(PlayerInventory inventory, ItemStack visualTemplate,
                                        String crateId, int amount) {
        validateAmount(amount);
        Map<Integer, ItemStack> overflow = new HashMap<>();
        int remaining = amount;
        int syntheticSlot = 0;
        while (remaining > 0) {
            ItemStack stack = createKey(visualTemplate, crateId,
                    Math.min(remaining, visualTemplate.getMaxStackSize()));
            remaining -= stack.getAmount();
            for (ItemStack leftover : inventory.addItem(stack).values()) {
                overflow.put(syntheticSlot++, leftover);
            }
        }
        return overflow;
    }

    private static String normalize(String crateId) {
        String normalized = Objects.requireNonNull(crateId, "crateId").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("crateId cannot be blank");
        }
        return normalized;
    }

    private static void validateAmount(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Key amount must be positive");
        }
    }
}
