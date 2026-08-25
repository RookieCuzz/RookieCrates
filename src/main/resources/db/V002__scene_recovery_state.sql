ALTER TABLE active_scene_recovery
    ADD COLUMN recovery_status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (recovery_status IN ('ACTIVE', 'APPLIED'));

ALTER TABLE active_scene_recovery
    ADD COLUMN applied_at INTEGER CHECK (applied_at IS NULL OR applied_at >= 0);

CREATE INDEX idx_active_scene_recovery_status
    ON active_scene_recovery(recovery_status, created_at, player_uuid);
