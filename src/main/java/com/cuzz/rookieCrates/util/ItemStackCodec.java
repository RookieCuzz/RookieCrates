package com.cuzz.rookieCrates.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * Version-aware Paper ItemStack snapshot codec. The serialized bytes preserve
 * item meta and PDC data, so captured custom items can be delivered later.
 */
public final class ItemStackCodec {

    public byte[] encode(ItemStack itemStack) {
        Objects.requireNonNull(itemStack, "itemStack");
        if (itemStack.getType() == Material.AIR || itemStack.getAmount() <= 0) {
            throw new IllegalArgumentException("Cannot serialize an empty item");
        }
        return itemStack.clone().serializeAsBytes();
    }

    public ItemStack decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("Item payload is empty");
        }
        return ItemStack.deserializeBytes(payload).clone();
    }
}
