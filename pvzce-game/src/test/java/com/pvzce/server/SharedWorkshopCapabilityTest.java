package com.pvzce.server;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.*;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedWorkshopCapabilityTest {
    @BeforeAll static void load()throws Exception{TestContent.loadBuiltInContentAndTags();}
    private LevelServer lawn(){return new LevelServer(TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"))).waves(List.of()).build());}
    @Test void aZombieUsesPlantProductionAndShootingWithItsOwnTeamAndDirection(){
        var json=JsonParser.parseString("""
            {"id":"test:producer_shooter","move_speed":0,"capabilities":[
             {"type":"pvzce:producer","resource":"pvzce:sun","every":2,"first_delay":1},
             {"type":"pvzce:shooter","interval":2,"shots":[{"projectile":"pvzce:pea","damage":20}]}]}
            """);
        ZombieDef def=ZombieDef.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
        LevelServer level=lawn();
        PlantEntity victim=level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut")),level.team(PvzceIds.PLANT_TEAM),2,0);
        ZombieEntity zombie=new ZombieEntity(def,level.team(PvzceIds.ZOMBIE_TEAM),5.5F,0);level.addEntity(zombie);level.flushPending(p->{});
        for(int tick=0;tick<30;tick++)level.tick(p->{});
        assertTrue(level.entities().stream().anyMatch(e->e instanceof ResourceDropEntity&&e.team()==zombie.team()));
        assertTrue(level.entities().stream().anyMatch(e->e instanceof ProjectileEntity&&e.cellX()<zombie.cellX()&&e.team()==zombie.team()));
        var saved=zombie.saveState();ZombieEntity restored=new ZombieEntity(def,zombie.team(),5.5F,0);restored.restoreState(saved);
        assertEquals(saved.getCompound("capabilities").getCompound("pvzce:producer").getInt("cooldown"),restored.saveState().getCompound("capabilities").getCompound("pvzce:producer").getInt("cooldown"));
        assertNotNull(victim);
    }
    @Test void repeatedProductionAbilitiesKeepIndependentClocksThroughSave(){
        ZombieDef def=ZombieDef.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("""
            {"id":"test:dual_producer","move_speed":0,"capabilities":[
             {"type":"pvzce:producer","resource":"pvzce:sun","every":10,"first_delay":1},
             {"type":"pvzce:producer","resource":"pvzce:coin_silver","every":30,"first_delay":20}]}
            """)).getOrThrow();
        LevelServer level=lawn();ZombieEntity original=new ZombieEntity(def,level.team(PvzceIds.ZOMBIE_TEAM),5.5F,0);
        level.addEntity(original);level.flushPending(p->{});for(int tick=0;tick<3;tick++)level.tick(p->{});
        var saved=original.saveState();var abilities=saved.getCompound("capabilities");
        assertNotEquals(abilities.getCompound("pvzce:producer").getInt("cooldown"),abilities.getCompound("pvzce:producer#2").getInt("cooldown"));
        ZombieEntity restored=new ZombieEntity(def,original.team(),5.5F,0);restored.restoreState(saved);
        var again=restored.saveState().getCompound("capabilities");
        assertEquals(abilities.getCompound("pvzce:producer").getInt("cooldown"),again.getCompound("pvzce:producer").getInt("cooldown"));
        assertEquals(abilities.getCompound("pvzce:producer#2").getInt("cooldown"),again.getCompound("pvzce:producer#2").getInt("cooldown"));
    }
    @Test void aPlantCanUseZombieArmourAndAnExplodingZombieHitsPlants(){
        PlantDef def=PlantDef.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("""
            {"id":"test:armoured","health":300,"capabilities":[{"type":"pvzce:armor","pieces":[{"id":"test:plate","durability":50,"position":"front"}]}]}
            """)).getOrThrow();
        LevelServer level=lawn();PlantEntity plant=level.spawnPlant(def,level.team(PvzceIds.PLANT_TEAM),2,0);level.flushPending(p->{});
        plant.damageFrom(40);assertEquals(300,plant.health());assertEquals(10,plant.armor());
        plant.damageFrom(40);assertEquals(0,plant.armor());plant.damageFrom(40);assertEquals(260,plant.health());
        ZombieDef bomb=ZombieDef.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("""
            {"id":"test:bomb","capabilities":[{"type":"pvzce:explosive","fuse_ticks":1,"damage":100,"radius":1.5}]}
            """)).getOrThrow();
        ZombieEntity zombie=new ZombieEntity(bomb,level.team(PvzceIds.ZOMBIE_TEAM),3F,0);level.addEntity(zombie);level.flushPending(p->{});
        for(int tick=0;tick<65;tick++)level.tick(p->{});
        assertTrue(plant.health()<260);
    }
}
