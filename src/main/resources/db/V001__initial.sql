CREATE TABLE scene_profiles (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    crate_model TEXT NOT NULL,
    loot_model TEXT NOT NULL
);

CREATE TABLE crates (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    key_item_blob BLOB,
    single_price REAL NOT NULL DEFAULT 0 CHECK (single_price >= 0),
    seven_price REAL NOT NULL DEFAULT 0 CHECK (seven_price >= 0),
    guarantee_a INTEGER NOT NULL DEFAULT 10 CHECK (guarantee_a > 0),
    guarantee_s INTEGER NOT NULL DEFAULT 80 CHECK (guarantee_s >= guarantee_a),
    scene_profile_id TEXT,
    broadcast_rarity TEXT DEFAULT 'S' CHECK (broadcast_rarity IS NULL OR broadcast_rarity IN ('S', 'A', 'B', 'C')),
    skip_allowed INTEGER NOT NULL DEFAULT 1 CHECK (skip_allowed IN (0, 1)),
    interaction_width REAL NOT NULL DEFAULT 1.5 CHECK (interaction_width > 0),
    interaction_height REAL NOT NULL DEFAULT 2.0 CHECK (interaction_height > 0),
    idle_animation TEXT NOT NULL DEFAULT 'idle',
    single_open_animation TEXT NOT NULL DEFAULT 'open1',
    seven_open_animation TEXT NOT NULL DEFAULT 'open7'
);

CREATE TABLE rewards (
    id TEXT PRIMARY KEY,
    crate_id TEXT NOT NULL REFERENCES crates(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    rarity TEXT NOT NULL CHECK (rarity IN ('S', 'A', 'B', 'C')),
    weight REAL NOT NULL CHECK (weight >= 0),
    display_scale REAL NOT NULL DEFAULT 0.6 CHECK (display_scale > 0 AND display_scale <= 4),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    broadcast INTEGER NOT NULL DEFAULT 0 CHECK (broadcast IN (0, 1))
);

CREATE INDEX idx_rewards_crate ON rewards(crate_id, enabled, rarity, id);

CREATE TABLE reward_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reward_id TEXT NOT NULL REFERENCES rewards(id) ON DELETE CASCADE,
    item_blob BLOB NOT NULL CHECK (length(item_blob) > 0),
    amount INTEGER NOT NULL CHECK (amount > 0)
);

CREATE INDEX idx_reward_items_reward ON reward_items(reward_id, id);

CREATE TABLE reward_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    reward_id TEXT NOT NULL REFERENCES rewards(id) ON DELETE CASCADE,
    command TEXT NOT NULL CHECK (length(trim(command)) > 0),
    execution_order INTEGER NOT NULL DEFAULT 0 CHECK (execution_order >= 0)
);

CREATE INDEX idx_reward_commands_reward ON reward_commands(reward_id, execution_order, id);

CREATE TABLE placements (
    placement_id TEXT PRIMARY KEY,
    crate_id TEXT NOT NULL REFERENCES crates(id) ON DELETE CASCADE,
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL
);

CREATE INDEX idx_placements_crate ON placements(crate_id, placement_id);
CREATE INDEX idx_placements_world ON placements(world);

CREATE TABLE scene_points (
    profile_id TEXT NOT NULL REFERENCES scene_profiles(id) ON DELETE CASCADE,
    point_index INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('CRATE', 'CAMERA', 'LOOT')),
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    PRIMARY KEY (profile_id, kind, point_index),
    CHECK ((kind = 'LOOT' AND point_index BETWEEN 1 AND 7)
        OR (kind IN ('CRATE', 'CAMERA') AND point_index = 1))
);

CREATE INDEX idx_scene_points_profile ON scene_points(profile_id, kind, point_index);

CREATE TABLE player_crate_state (
    player_uuid TEXT NOT NULL,
    crate_id TEXT NOT NULL REFERENCES crates(id) ON DELETE CASCADE,
    virtual_keys INTEGER NOT NULL DEFAULT 0 CHECK (virtual_keys >= 0),
    pity_a INTEGER NOT NULL DEFAULT 0 CHECK (pity_a >= 0),
    pity_s INTEGER NOT NULL DEFAULT 0 CHECK (pity_s >= 0),
    total_opens INTEGER NOT NULL DEFAULT 0 CHECK (total_opens >= 0),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, crate_id)
);

CREATE INDEX idx_player_crate_state_crate ON player_crate_state(crate_id, player_uuid);

CREATE TABLE open_transactions (
    id TEXT PRIMARY KEY,
    player_uuid TEXT NOT NULL,
    crate_id TEXT NOT NULL,
    draw_count INTEGER NOT NULL CHECK (draw_count IN (1, 7)),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'DRAWN', 'DELIVERING', 'COMPLETED', 'FAILED', 'RECOVERY_REQUIRED')),
    created_at INTEGER NOT NULL,
    completed_at INTEGER,
    failure_reason TEXT
);

CREATE INDEX idx_open_transactions_player ON open_transactions(player_uuid, created_at DESC);
CREATE INDEX idx_open_transactions_status ON open_transactions(status, created_at);

CREATE TABLE open_results (
    transaction_id TEXT NOT NULL REFERENCES open_transactions(id) ON DELETE CASCADE,
    result_index INTEGER NOT NULL CHECK (result_index BETWEEN 1 AND 7),
    reward_id TEXT NOT NULL,
    rarity TEXT NOT NULL CHECK (rarity IN ('S', 'A', 'B', 'C')),
    delivered INTEGER NOT NULL DEFAULT 0 CHECK (delivered IN (0, 1)),
    PRIMARY KEY (transaction_id, result_index)
);

CREATE INDEX idx_open_results_reward ON open_results(reward_id);

CREATE TABLE pending_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id TEXT NOT NULL,
    result_index INTEGER NOT NULL CHECK (result_index BETWEEN 1 AND 7),
    player_uuid TEXT NOT NULL,
    reward_id TEXT NOT NULL,
    item_blob BLOB,
    amount INTEGER NOT NULL DEFAULT 0,
    command TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    created_at INTEGER NOT NULL,
    delivered_at INTEGER,
    CHECK ((item_blob IS NOT NULL AND length(item_blob) > 0 AND amount > 0 AND command IS NULL)
        OR (item_blob IS NULL AND amount = 0 AND command IS NOT NULL AND length(trim(command)) > 0)),
    FOREIGN KEY (transaction_id, result_index)
        REFERENCES open_results(transaction_id, result_index) ON DELETE CASCADE
);

CREATE INDEX idx_pending_deliveries_work ON pending_deliveries(status, player_uuid, created_at, id);
CREATE INDEX idx_pending_deliveries_transaction ON pending_deliveries(transaction_id, result_index, id);

CREATE TABLE active_scene_recovery (
    player_uuid TEXT PRIMARY KEY,
    transaction_id TEXT NOT NULL REFERENCES open_transactions(id) ON DELETE CASCADE,
    world_uuid TEXT NOT NULL,
    world TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    game_mode TEXT NOT NULL,
    allow_flight INTEGER NOT NULL CHECK (allow_flight IN (0, 1)),
    flying INTEGER NOT NULL CHECK (flying IN (0, 1)),
    walk_speed REAL NOT NULL,
    fly_speed REAL NOT NULL,
    invulnerable INTEGER NOT NULL CHECK (invulnerable IN (0, 1)),
    collidable INTEGER NOT NULL CHECK (collidable IN (0, 1)),
    camera_entity_uuid TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_active_scene_recovery_transaction ON active_scene_recovery(transaction_id);
