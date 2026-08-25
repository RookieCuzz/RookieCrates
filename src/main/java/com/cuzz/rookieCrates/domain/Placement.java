package com.cuzz.rookieCrates.domain;

public record Placement(
        String placementId,
        String crateId,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public Placement {
        placementId = DomainChecks.required(placementId, "placementId");
        crateId = DomainChecks.required(crateId, "crateId");
        world = DomainChecks.required(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("placement coordinates must be finite");
        }
    }
}
