package com.cuzz.rookieCrates.domain;

import java.util.Objects;

public record ScenePoint(
        String profileId,
        int pointIndex,
        ScenePointKind kind,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public ScenePoint {
        profileId = DomainChecks.required(profileId, "profileId");
        kind = Objects.requireNonNull(kind, "kind");
        world = DomainChecks.required(world, "world");
        if (kind == ScenePointKind.LOOT) {
            if (pointIndex < 1 || pointIndex > 7) {
                throw new IllegalArgumentException("LOOT pointIndex must be in 1..7");
            }
        } else if (pointIndex != 1) {
            throw new IllegalArgumentException("CRATE and CAMERA pointIndex must be 1");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("scene coordinates must be finite");
        }
    }
}
