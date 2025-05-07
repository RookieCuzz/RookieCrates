package com.cuzz.rookieCrates.core.interfaces;

import com.cuzz.rookieCrates.core.model.GlobalLoot;

public interface LootManager {


    //查
    GlobalLoot getLootInfo(String lootid);
    //删除
    Boolean deleteLoot(String lootid);

    //改
    GlobalLoot editLoot(GlobalLoot loot);

    //增
    GlobalLoot addLoot(String  lootid);
    GlobalLoot addLoot(GlobalLoot loot);


}
