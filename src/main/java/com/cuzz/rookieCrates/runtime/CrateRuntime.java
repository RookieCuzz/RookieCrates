package com.cuzz.rookieCrates.runtime;

import com.cuzz.rookieCrates.runtime.model.RuntimeModelHandle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns public idle crate models and the Paper Interaction entities used as hitboxes. */
public final class CrateRuntime {
    private final Plugin plugin;
    private final NamespacedKey placementKey;
    private final Map<String, CratePlacement> placements = new LinkedHashMap<>();
    private final Map<ChunkKey, Map<String, CratePlacement>> placementsByChunk = new HashMap<>();
    private final Map<String, SpawnedPlacement> spawned = new HashMap<>();
    private final Map<UUID, String> interactionToPlacement = new HashMap<>();
    private final Map<String, Set<UUID>> hiddenViewers = new HashMap<>();
    private CrateInteractionFacade interactionFacade;

    public CrateRuntime(Plugin plugin) {
        this(plugin, (player, interaction) -> { });
    }

    public CrateRuntime(Plugin plugin, CrateInteractionFacade interactionFacade) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.placementKey = new NamespacedKey(plugin, "crate_placement");
        this.interactionFacade = Objects.requireNonNull(interactionFacade, "interactionFacade");
    }

    public void setInteractionFacade(CrateInteractionFacade interactionFacade) {
        requireMainThread();
        this.interactionFacade = Objects.requireNonNull(interactionFacade, "interactionFacade");
    }

    /** Replaces the placement snapshot and materializes every placement whose chunk is loaded. */
    public void reload(Collection<CratePlacement> newPlacements) {
        requireMainThread();
        Objects.requireNonNull(newPlacements, "newPlacements");

        Map<String, CratePlacement> validated = new LinkedHashMap<>();
        for (CratePlacement placement : newPlacements) {
            Objects.requireNonNull(placement, "newPlacements contains null");
            if (validated.putIfAbsent(placement.placementId(), placement) != null) {
                throw new IllegalArgumentException("duplicate placementId: " + placement.placementId());
            }
        }

        despawnAll();
        placements.clear();
        placementsByChunk.clear();
        placements.putAll(validated);
        for (CratePlacement placement : placements.values()) {
            Location location = placement.placementLocation();
            ChunkKey chunkKey = ChunkKey.of(location);
            placementsByChunk.computeIfAbsent(chunkKey, ignored -> new LinkedHashMap<>())
                    .put(placement.placementId(), placement);
            if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                spawn(placement);
            }
        }
    }

    public Optional<CratePlacement> findPlacement(String placementId) {
        return Optional.ofNullable(placements.get(placementId));
    }

    /** Adds or replaces one placement without rebuilding unrelated crate models. */
    public void upsertPlacement(CratePlacement placement) {
        requireMainThread();
        Objects.requireNonNull(placement, "placement");
        removePlacement(placement.placementId());
        placements.put(placement.placementId(), placement);
        Location location = placement.placementLocation();
        placementsByChunk.computeIfAbsent(ChunkKey.of(location), ignored -> new LinkedHashMap<>())
                .put(placement.placementId(), placement);
        if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            spawn(placement);
        }
    }

    /** Removes one placement's model and hitbox immediately when they are loaded. */
    public boolean removePlacement(String placementId) {
        requireMainThread();
        CratePlacement removed = placements.remove(placementId);
        if (removed == null) {
            return false;
        }
        despawn(placementId);
        ChunkKey chunkKey = ChunkKey.of(removed.placementLocation());
        Map<String, CratePlacement> inChunk = placementsByChunk.get(chunkKey);
        if (inChunk != null) {
            inChunk.remove(placementId);
            if (inChunk.isEmpty()) {
                placementsByChunk.remove(chunkKey);
            }
        }
        return true;
    }

    public void handleChunkLoad(Chunk chunk) {
        requireMainThread();
        Map<String, CratePlacement> inChunk = placementsByChunk.get(ChunkKey.of(chunk));
        if (inChunk != null) {
            inChunk.values().forEach(this::spawn);
        }
    }

    public void handleChunkUnload(Chunk chunk) {
        requireMainThread();
        Map<String, CratePlacement> inChunk = placementsByChunk.get(ChunkKey.of(chunk));
        if (inChunk != null) {
            for (String placementId : inChunk.keySet()) {
                despawn(placementId);
            }
        }
    }

    public void handleWorldLoad(World world) {
        requireMainThread();
        for (Chunk chunk : world.getLoadedChunks()) {
            handleChunkLoad(chunk);
        }
    }

    public void handleWorldUnload(World world) {
        requireMainThread();
        UUID worldId = world.getUID();
        spawned.entrySet().stream()
                .filter(entry -> entry.getValue().worldId().equals(worldId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::despawn);
    }

    /** Dispatches only hitboxes currently owned by this runtime. */
    public boolean handleMainHandRightClick(Player player, Entity clicked) {
        requireMainThread();
        String placementId = interactionToPlacement.get(clicked.getUniqueId());
        if (placementId == null) {
            return false;
        }
        CratePlacement placement = placements.get(placementId);
        if (placement == null) {
            return false;
        }
        try {
            interactionFacade.onMainHandRightClick(
                    player,
                    new CrateInteraction(placement.crateId(), placement.placementId())
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Crate interaction callback failed for placement '"
                    + placementId + "': " + exception.getMessage());
        }
        return true;
    }

    /** Temporarily hides or reveals the public idle model for one scene viewer. */
    public void setPlacementVisible(Player player, String placementId, boolean visible) {
        requireMainThread();
        if (visible) {
            Set<UUID> viewers = hiddenViewers.get(placementId);
            if (viewers != null) {
                viewers.remove(player.getUniqueId());
                if (viewers.isEmpty()) {
                    hiddenViewers.remove(placementId);
                }
            }
        } else {
            hiddenViewers.computeIfAbsent(placementId, ignored -> new HashSet<>())
                    .add(player.getUniqueId());
        }
        SpawnedPlacement placement = spawned.get(placementId);
        if (placement != null) {
            placement.model().setHidden(player, !visible);
        }
    }

    public void shutdown() {
        requireMainThread();
        despawnAll();
        placements.clear();
        placementsByChunk.clear();
        hiddenViewers.clear();
    }

    private void spawn(CratePlacement placement) {
        if (spawned.containsKey(placement.placementId())) {
            return;
        }
        Location location = placement.placementLocation();
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }

        RuntimeModelHandle model = null;
        Interaction interaction = null;
        try {
            removeStaleHitbox(location.getChunk(), placement.placementId());
            model = RuntimeModelHandle.spawnPublic(location, placement.crateModel());
            if (!model.playAnimation(placement.idleAnimation(), true)) {
                throw new IllegalArgumentException("Missing idle animation '" + placement.idleAnimation()
                        + "' for crate model '" + placement.crateModel() + "'");
            }
            for (UUID playerId : hiddenViewers.getOrDefault(placement.placementId(), Set.of())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    model.setHidden(player, true);
                }
            }
            interaction = location.getWorld().spawn(location, Interaction.class, hitbox -> {
                hitbox.setInteractionWidth(placement.interactionWidth());
                hitbox.setInteractionHeight(placement.interactionHeight());
                hitbox.setResponsive(true);
                hitbox.setPersistent(false);
                hitbox.getPersistentDataContainer().set(
                        placementKey,
                        PersistentDataType.STRING,
                        placement.placementId()
                );
            });
            spawned.put(
                    placement.placementId(),
                    new SpawnedPlacement(location.getWorld().getUID(), model, interaction)
            );
            interactionToPlacement.put(interaction.getUniqueId(), placement.placementId());
        } catch (RuntimeException exception) {
            if (interaction != null) {
                try {
                    interaction.remove();
                } catch (RuntimeException cleanupFailure) {
                    plugin.getLogger().severe("Could not remove failed crate hitbox: "
                            + cleanupFailure.getMessage());
                }
            }
            if (model != null) {
                try {
                    model.remove();
                } catch (RuntimeException cleanupFailure) {
                    plugin.getLogger().severe("Could not remove failed crate model: "
                            + cleanupFailure.getMessage());
                }
            }
            plugin.getLogger().severe("Could not spawn crate placement '" + placement.placementId()
                    + "': " + exception.getMessage());
        }
    }

    private void removeStaleHitbox(Chunk chunk, String placementId) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Interaction)) {
                continue;
            }
            String ownedPlacement = entity.getPersistentDataContainer().get(placementKey, PersistentDataType.STRING);
            if (placementId.equals(ownedPlacement)) {
                entity.remove();
                interactionToPlacement.remove(entity.getUniqueId());
            }
        }
    }

    private void despawn(String placementId) {
        SpawnedPlacement old = spawned.remove(placementId);
        if (old == null) {
            return;
        }
        interactionToPlacement.remove(old.interaction().getUniqueId());
        try {
            old.interaction().remove();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not remove crate hitbox for placement '"
                    + placementId + "': " + exception.getMessage());
        }
        try {
            old.model().remove();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not remove crate model for placement '"
                    + placementId + "': " + exception.getMessage());
        }
    }

    private void despawnAll() {
        spawned.keySet().stream().toList().forEach(this::despawn);
        interactionToPlacement.clear();
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("CrateRuntime must be called on the Bukkit main thread");
        }
    }

    private record SpawnedPlacement(UUID worldId, RuntimeModelHandle model, Interaction interaction) { }

    private record ChunkKey(UUID worldId, int x, int z) {
        private static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }

        private static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
