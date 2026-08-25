package com.cuzz.rookieCrates.domain;

public record SceneProfile(String id, String name, String crateModel, String lootModel) {
    public SceneProfile {
        id = DomainChecks.required(id, "id");
        name = DomainChecks.required(name, "name");
        crateModel = DomainChecks.required(crateModel, "crateModel");
        lootModel = DomainChecks.required(lootModel, "lootModel");
    }
}
