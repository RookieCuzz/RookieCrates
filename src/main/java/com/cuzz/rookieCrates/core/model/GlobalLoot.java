package com.cuzz.rookieCrates.core.model;

public class GlobalLoot {
    //id
    String id;

    //展示名字
    String displayName;

    //物品实体
    String lootItemStackBase64;

    //icon Lore
    String lores;

    //描述
    String description;


    public GlobalLoot() {}

    public GlobalLoot(com.cuzz.rookiecrates.model.DCrateLoot dCrateLoot) {
        this.setId(dCrateLoot.getId());
        this.setDescription(dCrateLoot.getDescription());
        this.setDisplayName(dCrateLoot.getName());
        this.setLores(dCrateLoot.getLores());
        this.setLootItemStackBase64(dCrateLoot.getLootItemstackBase64());
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getLootItemStackBase64() {
        return lootItemStackBase64;
    }

    public void setLootItemStackBase64(String lootItemStackBase64) {

        this.lootItemStackBase64 = lootItemStackBase64;

    }

    public String getLores() {
        return lores;
    }

    public void setLores(String lores) {
        this.lores = lores;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
