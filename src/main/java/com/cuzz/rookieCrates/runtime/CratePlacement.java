package com.cuzz.rookieCrates.runtime;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runtime-only view of a configured physical crate and its scene profile. */
public record CratePlacement(
        String crateId,
        String placementId,
        Location placementLocation,
        Location crateLocation,
        Location cameraLocation,
        List<Location> lootLocations,
        float interactionWidth,
        float interactionHeight,
        String crateModel,
        String idleAnimation
) {
    public CratePlacement {
        crateId = requireText(crateId, "crateId");
        placementId = requireText(placementId, "placementId");
        placementLocation = cloneLocation(placementLocation, "placementLocation");
        crateLocation = cloneLocation(crateLocation, "crateLocation");
        cameraLocation = cloneLocation(cameraLocation, "cameraLocation");
        Objects.requireNonNull(lootLocations, "lootLocations");
        if (lootLocations.size() != 7) {
            throw new IllegalArgumentException("lootLocations must contain exactly seven positions");
        }
        List<Location> copiedLocations = new ArrayList<>(7);
        for (int i = 0; i < lootLocations.size(); i++) {
            Location copied = cloneLocation(lootLocations.get(i), "lootLocations[" + i + "]");
            requireSameWorld(crateLocation, copied, "lootLocations[" + i + "]");
            copiedLocations.add(copied);
        }
        requireSameWorld(crateLocation, cameraLocation, "cameraLocation");
        lootLocations = List.copyOf(copiedLocations);
        if (!Float.isFinite(interactionWidth) || interactionWidth <= 0.0F) {
            throw new IllegalArgumentException("interactionWidth must be positive and finite");
        }
        if (!Float.isFinite(interactionHeight) || interactionHeight <= 0.0F) {
            throw new IllegalArgumentException("interactionHeight must be positive and finite");
        }
        crateModel = requireText(crateModel, "crateModel");
        idleAnimation = requireText(idleAnimation, "idleAnimation");
    }

    @Override
    public Location placementLocation() {
        return placementLocation.clone();
    }

    @Override
    public Location crateLocation() {
        return crateLocation.clone();
    }

    @Override
    public Location cameraLocation() {
        return cameraLocation.clone();
    }

    @Override
    public List<Location> lootLocations() {
        return lootLocations.stream().map(Location::clone).toList();
    }

    private static Location cloneLocation(Location location, String name) {
        Objects.requireNonNull(location, name);
        if (location.getWorld() == null) {
            throw new IllegalArgumentException(name + " must have a loaded world");
        }
        return location.clone();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireSameWorld(Location crateLocation, Location other, String name) {
        if (!crateLocation.getWorld().getUID().equals(other.getWorld().getUID())) {
            throw new IllegalArgumentException(name + " must be in the crate world");
        }
    }
}
