package com.cuzz.rookieCrates.runtime.model;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.BlueprintAnimation;
import com.ticxo.modelengine.api.animation.property.SimpleProperty;
import com.ticxo.modelengine.api.entity.BaseEntity;
import com.ticxo.modelengine.api.entity.Dummy;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes;
import com.ticxo.modelengine.api.model.bone.type.HeldItem;
import com.ticxo.modelengine.api.model.bone.type.NameTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/** Small, idempotently removable wrapper around a ModelEngine dummy. */
public final class RuntimeModelHandle {
    private final Dummy<?> dummy;
    private final ModeledEntity modeledEntity;
    private final ActiveModel activeModel;
    private final String modelId;
    private boolean removed;

    private RuntimeModelHandle(
            Dummy<?> dummy,
            ModeledEntity modeledEntity,
            ActiveModel activeModel,
            String modelId
    ) {
        this.dummy = dummy;
        this.modeledEntity = modeledEntity;
        this.activeModel = activeModel;
        this.modelId = modelId;
    }

    public static RuntimeModelHandle spawnPublic(Location location, String modelId) {
        return spawn(location, modelId, null);
    }

    public static RuntimeModelHandle spawnPrivate(Location location, String modelId, Player viewer) {
        return spawn(location, modelId, Objects.requireNonNull(viewer, "viewer"));
    }

    private static RuntimeModelHandle spawn(Location location, String modelId, Player viewer) {
        requireMainThread();
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location.world");
        Objects.requireNonNull(modelId, "modelId");

        Dummy<?> dummy = new Dummy<>();
        ModeledEntity modeledEntity = null;
        ActiveModel activeModel = null;
        String registeredModelId = null;
        boolean attached = false;
        try {
            dummy.setDetectingPlayers(viewer == null);
            dummy.syncLocation(location.clone());
            modeledEntity = ModelEngineAPI.createModeledEntity((BaseEntity<?>) dummy);
            activeModel = Objects.requireNonNull(
                    ModelEngineAPI.createActiveModel(modelId),
                    "Unknown ModelEngine model: " + modelId
            );
            registeredModelId = activeModel.getBlueprint().getName();
            activeModel.setCanHurt(false);
            activeModel.setSkyLight(15);
            activeModel.setBlockLight(15);
            attachModel(modeledEntity, activeModel, registeredModelId);
            attached = true;
            if (viewer != null) {
                dummy.setForceViewing(viewer, true);
            }
            return new RuntimeModelHandle(dummy, modeledEntity, activeModel, registeredModelId);
        } catch (RuntimeException exception) {
            cleanupFailedSpawn(dummy, modeledEntity, activeModel, registeredModelId, attached, exception);
            throw exception;
        }
    }

    private static void cleanupFailedSpawn(
            Dummy<?> dummy,
            ModeledEntity modeledEntity,
            ActiveModel activeModel,
            String registeredModelId,
            boolean attached,
            RuntimeException original
    ) {
        if (modeledEntity == null) {
            destroySuppressing(activeModel, original);
            return;
        }
        try {
            if (attached && registeredModelId != null) {
                try {
                    modeledEntity.removeModel(registeredModelId)
                            .ifPresent(model -> destroySuppressing(model, original));
                } catch (RuntimeException cleanupFailure) {
                    original.addSuppressed(cleanupFailure);
                }
            } else {
                destroySuppressing(activeModel, original);
            }
            try {
                modeledEntity.markRemoved();
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        } finally {
            try {
                ModelEngineAPI.removeModeledEntity(dummy.getUUID());
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void destroySuppressing(ActiveModel model, RuntimeException original) {
        if (model == null) {
            return;
        }
        try {
            model.destroy();
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    /**
     * ModelEngine returns the model that was replaced, not the model just added. Therefore an
     * empty Optional is the normal first-add result. Verify attachment through getModel instead.
     */
    static void attachModel(ModeledEntity modeledEntity, ActiveModel activeModel, String modelId) {
        Optional<ActiveModel> replaced = modeledEntity.addModel(activeModel, true);
        if (modeledEntity.getModel(modelId).filter(attached -> attached == activeModel).isEmpty()) {
            throw new IllegalStateException("ModelEngine rejected model: " + modelId);
        }
        replaced.filter(previous -> previous != activeModel).ifPresent(ActiveModel::destroy);
    }

    public boolean playAnimation(String animationId, boolean loop) {
        requireMainThread();
        if (removed) {
            return false;
        }
        BlueprintAnimation animation = activeModel.getBlueprint().getAnimations().get(animationId);
        if (animation == null) {
            return false;
        }
        SimpleProperty property = new SimpleProperty(activeModel, animation, 0.05D, 0.05D, 1.0D);
        property.setForceLoopMode(loop ? BlueprintAnimation.LoopMode.LOOP : BlueprintAnimation.LoopMode.ONCE);
        return activeModel.getAnimationHandler().playAnimation(property, true);
    }

    public void configureLoot(ItemStack item, String displayName) {
        requireMainThread();
        if (removed) {
            return;
        }
        setLootItem(activeModel, item);
        activeModel.getBone("tag_name")
                .flatMap(bone -> bone.getBoneBehavior(BoneBehaviorTypes.NAMETAG))
                .filter(NameTag.class::isInstance)
                .map(NameTag.class::cast)
                .ifPresent(nameTag -> {
                    nameTag.setString(displayName);
                    nameTag.setVisible(true);
                });
    }

    /** Uses ModelEngine's held-item renderer; a plain empty bone has no renderer of its own. */
    static void setLootItem(ActiveModel activeModel, ItemStack item) {
        HeldItem heldItem = lootItemBehavior(activeModel);
        heldItem.setItemProvider(new HeldItem.StaticItemStackSupplier(item.clone()));
    }

    static HeldItem lootItemBehavior(ActiveModel activeModel) {
        return activeModel.getBone("item")
                .orElseThrow(() -> new IllegalStateException("Loot model has no item bone"))
                .getBoneBehavior(BoneBehaviorTypes.ITEM)
                .map(HeldItem.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        "Loot item bone has no ModelEngine held-item behavior; name it ih_item"
                ));
    }

    public void setHidden(Player player, boolean hidden) {
        requireMainThread();
        if (!removed) {
            dummy.setForceHidden(player, hidden);
        }
    }

    public void remove() {
        requireMainThread();
        if (removed) {
            return;
        }
        removed = true;
        try {
            modeledEntity.removeModel(modelId).ifPresent(ActiveModel::destroy);
        } finally {
            try {
                modeledEntity.markRemoved();
            } finally {
                ModelEngineAPI.removeModeledEntity(dummy.getUUID());
            }
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("ModelEngine operations must run on the Bukkit main thread");
        }
    }
}
