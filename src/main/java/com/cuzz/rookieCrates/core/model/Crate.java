package com.cuzz.rookieCrates.core.model;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class Crate {

    String id;
    String displayName;
    //奖品池

    List<CrateLoot> crateLoots;
    private Random random = new Random();

    public Crate(String id) {
        this.id = id;
    }

    private CrateLoot drawPrize(Double luckFactor) {
        double x = 0;

        // 稀有奖品的额外权重
        if (luckFactor > 100) {
            x = luckFactor * 0.01;
        }

        if (crateLoots == null || crateLoots.isEmpty()) {
            throw new IllegalArgumentException("奖品列表不能为空");
        }

        // 计算总权重
        double totalWeight = 0.0;
        for (CrateLoot prize : crateLoots) {
            if (prize.getRarity() == Rarity.A) {
                totalWeight += prize.getWeight() + x; // 对稀有奖品增加额外权重
            } else {
                totalWeight += prize.getWeight();
            }
        }

        // 生成随机数
        double randomValue = random.nextDouble() * totalWeight;

        // 累加权重，选择奖品
        double accumulatedWeight = 0.0;
        for (CrateLoot prize : crateLoots) {
            double adjustedWeight = prize.getWeight();
            if (prize.getRarity() == Rarity.A) {
                adjustedWeight += x; // 对稀有奖品增加额外权重
            }
            accumulatedWeight += adjustedWeight;
            if (randomValue <= accumulatedWeight) {
                return prize;
            }
        }

        // 如果没有选中任何奖品（理论上不会发生），返回最后一个奖品
        return crateLoots.get(crateLoots.size() - 1);
    }

    //打开一次宝箱
    public CrateLoot generateLoot(Player player){
        CrateLoot crateLoot = this.drawPrize(100d);
        return crateLoot;
    }

    public Crate addLoot(CrateLoot crateLoot){
        this.crateLoots.add(crateLoot);
        return this;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Crate addLoot(GlobalLoot globalLoot,String id,double weight,int amount){
        CrateLoot crateLoot = new CrateLoot(globalLoot);
        crateLoot.setCrateId(id);
        crateLoot.setWeight(weight);
        crateLoot.setAmount(amount);
        this.crateLoots.add(crateLoot);
        return this;
    }
}
