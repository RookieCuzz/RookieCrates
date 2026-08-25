package com.cuzz.rookieCrates.runtime;

import org.bukkit.entity.Player;

/** Business-layer callback invoked by a main-hand click on a crate hitbox. */
@FunctionalInterface
public interface CrateInteractionFacade {
    void onMainHandRightClick(Player player, CrateInteraction interaction);
}
