package com.cuzz.rookieCrates.core.interfaces;

import com.cuzz.rookieCrates.core.model.Crate;
import com.cuzz.rookieCrates.core.model.GlobalLoot;

public interface CrateManager {

    //查
    Crate getCrateInfo(String crateId);

    //删
    Boolean deleteCrate(String crateId);

    //增
    Crate createCrate(String crateId, String crateName);

    //改
    Crate editCrate(Crate newCrate);

    //添加loot到对应的crate
    Crate addLootToCrate(String crateId,String LootId);
    Crate addLootToCrate(Crate crate,String LootId);
    Crate addLootToCrate(Crate crate, GlobalLoot loot);
}
