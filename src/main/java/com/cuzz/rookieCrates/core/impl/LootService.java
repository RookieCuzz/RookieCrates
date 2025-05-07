package com.cuzz.rookieCrates.core.impl;

import com.cuzz.bukkitmybatis.BukkitMybatis;
import com.cuzz.rookieCrates.core.interfaces.LootManager;
import com.cuzz.rookieCrates.core.model.GlobalLoot;
import org.apache.ibatis.session.SqlSession;
import com.cuzz.rookiecrates.mapper.DCrateLootMapper;
import com.cuzz.rookiecrates.model.DCrateLoot;

public class LootService implements LootManager {
    @Override
    public GlobalLoot getLootInfo(String lootid) {
        if(lootid == null || lootid.isEmpty()){
            throw new IllegalArgumentException("Loot ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = crateLootMapper.selectByPrimaryKey(lootid);
            if(dCrateLoot == null){
                throw new IllegalArgumentException("Loot ID does not exist");
            }
            return new GlobalLoot(dCrateLoot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get loot info", e);
        }
    }

    @Override
    public Boolean deleteLoot(String lootid) {
        if(lootid == null || lootid.isEmpty()){
            throw new IllegalArgumentException("Loot ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            int result = crateLootMapper.deleteByPrimaryKey(lootid);
            sqlSession.commit();
            return result > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete loot", e);
        }
    }

    @Override
    public GlobalLoot editLoot(GlobalLoot loot) {
        if(loot == null){
            throw new IllegalArgumentException("Loot cannot be null");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = crateLootMapper.selectByPrimaryKey(loot.getId());
            if(dCrateLoot == null){
                throw new IllegalArgumentException("Loot ID does not exist");
            }
            dCrateLoot.setId(loot.getId());
            dCrateLoot.setName(loot.getDisplayName());
            dCrateLoot.setDescription(loot.getDescription());
            dCrateLoot.setLores(loot.getLores());
            dCrateLoot.setLootItemstackBase64(loot.getLootItemStackBase64());
            crateLootMapper.updateByPrimaryKey(dCrateLoot);
            sqlSession.commit();
            return loot;
        } catch (Exception e) {
            throw new RuntimeException("Failed to edit loot", e);
        }
    }

    @Override
    public GlobalLoot addLoot(String lootid) {
        if(lootid == null || lootid.isEmpty()){
            throw new IllegalArgumentException("Loot ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = new DCrateLoot();
            dCrateLoot.setId(lootid);
            crateLootMapper.insert(dCrateLoot);
            sqlSession.commit();
            return new GlobalLoot(dCrateLoot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add loot", e);
        }
    }

    @Override
    public GlobalLoot addLoot(GlobalLoot loot) {
        if(loot == null){
            throw new IllegalArgumentException("Loot cannot be null");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = new DCrateLoot();
            dCrateLoot.setId(loot.getId());
            dCrateLoot.setName(loot.getDisplayName());
            dCrateLoot.setDescription(loot.getDescription());
            dCrateLoot.setLores(loot.getLores());
            dCrateLoot.setLootItemstackBase64(loot.getLootItemStackBase64());
            crateLootMapper.insert(dCrateLoot);
            sqlSession.commit();
            return loot;
        } catch (Exception e) {
            throw new RuntimeException("Failed to add loot", e);
        }
    }
}
