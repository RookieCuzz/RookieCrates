package com.cuzz.rookieCrates.core.impl;

import com.cuzz.bukkitmybatis.BukkitMybatis;
import com.cuzz.rookieCrates.core.interfaces.CrateManager;
import com.cuzz.rookieCrates.core.model.Crate;
import com.cuzz.rookiecrates.model.DCrate;
import com.cuzz.rookieCrates.core.model.GlobalLoot;
import org.apache.ibatis.session.SqlSession;
import com.cuzz.rookiecrates.mapper.DCrateLootMapper;
import com.cuzz.rookiecrates.model.DCrateLoot;
import com.cuzz.rookiecrates.model.DCrateLootExample;
import com.cuzz.rookiecrates.mapper.DCrateMapper;

import java.util.List;

public class CrateServiceImpl implements CrateManager {

    @Override
    public Crate getCrateInfo(String crateId) {
        if (crateId == null || crateId.trim().isEmpty()) {
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }

        Crate newCrate = new Crate(crateId);

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLootExample dCrateLootExample = new DCrateLootExample();
            dCrateLootExample.createCriteria().andCrateIdEqualTo(crateId);
            List<DCrateLoot> dCrateLoots = crateLootMapper.selectByExample(dCrateLootExample);
            if (dCrateLoots != null) {
                for (DCrateLoot dCrateLoot : dCrateLoots) {
                    GlobalLoot loot = new GlobalLoot(dCrateLoot);
                    newCrate.addLoot(loot, dCrateLoot.getId(), dCrateLoot.getWeight(), dCrateLoot.getAmount());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error creating crate", e);
        }

        return newCrate;
    }

    @Override
    public Boolean deleteCrate(String crateId) {
        if(crateId == null || crateId.trim().isEmpty()){
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateMapper dCrateMapper = sqlSession.getMapper(DCrateMapper.class);
            int result = dCrateMapper.deleteByPrimaryKey(crateId);
            sqlSession.commit();
            return result > 0;
        } catch (Exception e) {
            throw new RuntimeException("Error deleting crate", e);
        }
    }

    @Override
    public Crate createCrate(String crateId, String crateName) {
        if(crateId == null || crateId.trim().isEmpty()){
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateMapper dCrateMapper = sqlSession.getMapper(DCrateMapper.class);
            DCrate dCrate = new DCrate();
            dCrate.setId(crateId);
            dCrate.setName(crateName);
            dCrateMapper.insert(dCrate);
            sqlSession.commit();
            return new Crate(crateId);
        } catch (Exception e) {
            throw new RuntimeException("Error creating crate", e);
        }
    }

    @Override
    public Crate editCrate(Crate crate) {
        if(crate == null){
            throw new IllegalArgumentException("Crate cannot be null");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateMapper dCrateMapper = sqlSession.getMapper(DCrateMapper.class);
            DCrate dCrate = dCrateMapper.selectByPrimaryKey(crate.getId());
            if(dCrate == null){
                throw new IllegalArgumentException("Crate ID does not exist");
            }
            dCrate.setId(crate.getId());
            dCrate.setName(crate.getDisplayName());
            dCrateMapper.updateByPrimaryKey(dCrate);
            sqlSession.commit();
            return crate;
        } catch (Exception e) {
            throw new RuntimeException("Error editing crate", e);
        }
    }

    @Override
    public Crate addLootToCrate(String crateId, String LootId) {
        if(crateId == null || crateId.trim().isEmpty()){
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }
        if(LootId == null || LootId.trim().isEmpty()){
            throw new IllegalArgumentException("Loot ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = crateLootMapper.selectByPrimaryKey(LootId);
            if(dCrateLoot == null){
                throw new IllegalArgumentException("Loot ID does not exist");
            }
            dCrateLoot.setCrateId(crateId);
            crateLootMapper.updateByPrimaryKey(dCrateLoot);
            sqlSession.commit();

            return getCrateInfo(crateId);
        } catch (Exception e) {
            throw new RuntimeException("Error adding loot to crate", e);
        }
    }

    @Override
    public Crate addLootToCrate(Crate crate, String LootId) {
        if(crate == null){
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }
        if(LootId == null || LootId.trim().isEmpty()){
            throw new IllegalArgumentException("Loot ID cannot be null or empty");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = crateLootMapper.selectByPrimaryKey(LootId);
            if(dCrateLoot == null){
                throw new IllegalArgumentException("Loot ID does not exist");
            }
            dCrateLoot.setCrateId(crate.getId());
            crateLootMapper.updateByPrimaryKey(dCrateLoot);
            sqlSession.commit();

            return getCrateInfo(crate.getId());
        } catch (Exception e) {
            throw new RuntimeException("Error adding loot to crate", e);
        }
    }

    @Override
    public Crate addLootToCrate(Crate crate, GlobalLoot loot) {
        if(crate == null){
            throw new IllegalArgumentException("Crate ID cannot be null or empty");
        }
        if(loot == null){
            throw new IllegalArgumentException("Loot cannot be null");
        }

        try (SqlSession sqlSession = BukkitMybatis.instance.getSqlSessionFactory().openSession()){
            DCrateLootMapper crateLootMapper = sqlSession.getMapper(DCrateLootMapper.class);
            DCrateLoot dCrateLoot = crateLootMapper.selectByPrimaryKey(loot.getId());
            if(dCrateLoot == null){
                throw new IllegalArgumentException("Loot ID does not exist");
            }
            dCrateLoot.setCrateId(crate.getId());
            crateLootMapper.updateByPrimaryKey(dCrateLoot);
            sqlSession.commit();

            return getCrateInfo(crate.getId());
        } catch (Exception e) {
            throw new RuntimeException("Error adding loot to crate", e);
        }
    }
}