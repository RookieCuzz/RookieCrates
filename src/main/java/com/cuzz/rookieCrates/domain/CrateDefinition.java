package com.cuzz.rookieCrates.domain;

public record CrateDefinition(
        String id,
        String displayName,
        boolean enabled,
        byte[] keyItemBlob,
        double singlePrice,
        double sevenPrice,
        int guaranteeA,
        int guaranteeS,
        String sceneProfileId,
        Rarity broadcastRarity,
        boolean skipAllowed,
        double interactionWidth,
        double interactionHeight,
        String idleAnimation,
        String singleOpenAnimation,
        String sevenOpenAnimation
) {
    public static final int DEFAULT_GUARANTEE_A = 10;
    public static final int DEFAULT_GUARANTEE_S = 80;

    public CrateDefinition {
        id = DomainChecks.required(id, "id");
        displayName = DomainChecks.required(displayName, "displayName");
        keyItemBlob = DomainChecks.copy(keyItemBlob);
        if (!Double.isFinite(singlePrice) || singlePrice < 0.0) {
            throw new IllegalArgumentException("singlePrice must be finite and non-negative");
        }
        if (!Double.isFinite(sevenPrice) || sevenPrice < 0.0) {
            throw new IllegalArgumentException("sevenPrice must be finite and non-negative");
        }
        if (guaranteeA <= 0 || guaranteeS <= 0 || guaranteeA > guaranteeS) {
            throw new IllegalArgumentException("guarantees must satisfy 0 < A <= S");
        }
        sceneProfileId = DomainChecks.nullable(sceneProfileId);
        if (!Double.isFinite(interactionWidth) || interactionWidth <= 0.0) {
            throw new IllegalArgumentException("interactionWidth must be finite and positive");
        }
        if (!Double.isFinite(interactionHeight) || interactionHeight <= 0.0) {
            throw new IllegalArgumentException("interactionHeight must be finite and positive");
        }
        idleAnimation = DomainChecks.required(idleAnimation, "idleAnimation");
        singleOpenAnimation = DomainChecks.required(singleOpenAnimation, "singleOpenAnimation");
        sevenOpenAnimation = DomainChecks.required(sevenOpenAnimation, "sevenOpenAnimation");
    }

    @Override
    public byte[] keyItemBlob() {
        return DomainChecks.copy(keyItemBlob);
    }

    public String openAnimationFor(int drawCount) {
        return switch (drawCount) {
            case 1 -> singleOpenAnimation;
            case 7 -> sevenOpenAnimation;
            default -> throw new IllegalArgumentException("drawCount must be 1 or 7");
        };
    }
}
