package com.pvzce.server;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.entity.attribute.AttributeContainer;
import com.pvzce.api.entity.attribute.AttributeModifier;
import com.pvzce.api.entity.attribute.AttributeOverrides;
import com.pvzce.api.entity.attribute.EntityAttribute;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.entity.EntityAttributes;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntityAttributesS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EntityAttributesTest {
    @BeforeAll static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags().close();
    }

    private static Identifier id(String path) { return Identifier.withDefaultNamespace(path); }
    private static AttributeModifier total(String name, double amount) {
        return new AttributeModifier(id(name), amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }
    private static AttributeOverrides miniature(String zombie) {
        return BuiltInRegistries.LEVELS.get(id("yard/adventure/3_5")).waves().stream()
                .flatMap(wave -> wave.entries().stream()).filter(entry -> entry.id().equals(id(zombie)))
                .findFirst().orElseThrow().attributes();
    }
    private static LevelDef fixture(List<WaveDef> waves) {
        return TestLevels.copy(BuiltInRegistries.LEVELS.get(id("yard/adventure/1_1")))
                .waves(waves).initialEntities(List.of()).mechanics(List.of()).build();
    }
    private static LevelServer quietLevel() {
        return new LevelServer(fixture(List.of(new WaveDef(WaveDef.WaveType.FINAL, 100_000, 0,
                List.of(new WaveDef.Entry(id("basic_zombie"), 1))))), 42L);
    }

    private static List<ZombieEntity> zombies(LevelServer level) {
        return level.entities().stream().filter(ZombieEntity.class::isInstance).map(ZombieEntity.class::cast).toList();
    }

    @Test void registeredCustomAttributeStacksAndRestoresNamedModifiers() {
        Identifier custom = Identifier.parse("example:reach");
        EntityAttribute definition = new EntityAttribute(custom, 100D, 0D, 200D, true);
        Map<Identifier, EntityAttribute> registry = Map.of(custom, definition);
        AttributeContainer first = new AttributeContainer(registry::get, ignored -> { });
        var value = first.add(custom);
        value.setModifier(new AttributeModifier(id("flat"), 20D, AttributeModifier.Operation.ADD_VALUE));
        value.setModifier(new AttributeModifier(id("base"), .5D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        value.setModifier(total("total", .5D));
        value.setModifier(total("small", -.5D));
        assertEquals(135D, value.value());
        value.setModifier(total("small", -.5D));
        assertEquals(135D, value.value(), "reapplying an effect replaces its named contribution");
        AttributeContainer other = new AttributeContainer(registry::get, ignored -> { });
        assertEquals(100D, other.add(custom).value());
        other.restore(first.save());
        other.restore(first.save());
        assertEquals(135D, other.value(custom), "restoring twice never doubles modifiers");
        other.get(custom).removeModifier(id("small"));
        assertEquals(200D, other.value(custom), "clamp after all three operations");
        assertThrows(IllegalArgumentException.class, () -> value.setBaseValue(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> first.add(Identifier.parse("example:unknown")));
        assertFalse(EntityAttribute.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"id\":\"example:bad\",\"default\":5,\"min\":10,\"max\":1}")).isSuccess());
        value.setModifier(new AttributeModifier(id("temporary"), -10D,
                AttributeModifier.Operation.ADD_VALUE, false));
        other.restore(first.save());
        assertEquals(135D, other.value(custom), "transient modifiers are omitted from persistence");
    }

    @Test void instanceValuesDriveMovementAndRealBites() {
        LevelServer level = quietLevel();
        ZombieEntity ordinary = level.spawnZombie(id("basic_zombie"), 7F, 1);
        ZombieEntity small = level.spawnZombie(id("basic_zombie"), 7F, 2);
        small.applySpawnAttributes(miniature("basic_zombie"));
        level.flushPending(packet -> { });
        float before = small.cellX();
        level.tick(packet -> { });
        assertEquals(before - BuiltInRegistries.ZOMBIES.get(id("basic_zombie")).moveSpeed() * 2F / com.pvzce.common.PvzceConstants.TICKS_PER_SECOND,
                small.cellX(), 1e-5F);
        assertEquals(100, small.maxHealth());
        assertEquals(200, ordinary.maxHealth());
        assertEquals(1F, ordinary.renderScale());

        var wall = level.spawnPlant(BuiltInRegistries.PLANTS.get(id("wall_nut")),
                level.team(PvzceIds.PLANT_TEAM), 0, 0);
        var biter = level.spawnZombie(id("basic_zombie"), .9F, 0);
        biter.attributes().get(EntityAttributes.ATTACK_DAMAGE).setBaseValue(11);
        biter.attributes().get(EntityAttributes.ATTACK_INTERVAL).setBaseValue(2);
        level.flushPending(packet -> { });
        int health = wall.health();
        for (int tick = 0; tick < 7; tick++) level.tick(packet -> { });
        assertEquals(health - 33, wall.health(), "damage and interval both affect actual bites");
    }

    @Test void healthGrowthAndWornArmourSurviveEntityRestore() {
        LevelServer level = quietLevel();
        var definition = BuiltInRegistries.ZOMBIES.get(id("conehead_zombie"));
        ZombieEntity entity = new ZombieEntity(definition, null, 6F, 0, 3F, miniature("conehead_zombie"));
        entity.damageBody(30, level);
        entity.capability(com.pvzce.common.capability.zombie.ArmorCapability.class).onImpact(entity, 40, level);
        CompoundTag state = entity.saveState();
        ZombieEntity restored = new ZombieEntity(definition, null, 0F, 0);
        restored.restoreState(state);
        assertEquals(300, restored.maxHealth());
        assertEquals(270, restored.health());
        assertEquals(145, restored.armorHealth());
        assertEquals(.5F, restored.renderScale());
        assertEquals(entity.moveSpeed(level), restored.moveSpeed(level));
        restored.restoreState(state);
        assertEquals(300, restored.maxHealth());
        assertEquals(145, restored.armorHealth());
    }

    @Test void bossPhaseFractionsUseTheInstanceMaximum() {
        LevelServer level = quietLevel();
        ZombieEntity zombie = level.spawnZombie(id("basic_zombie"), 7F, 0);
        zombie.attributes().get(EntityAttributes.MAX_HEALTH).setBaseValue(50);
        var phases = new com.pvzce.common.capability.zombie.BossPhasesCapability(List.of(
                new com.pvzce.api.content.BossPhaseDef(.5F, List.of(), "")), 0F);
        CompoundTag state = new CompoundTag();
        phases.tick(zombie, level);
        phases.save(state);
        assertEquals(0, state.getInt("nextPhaseIndex"), "a full-health instance has not crossed half health");
        zombie.damageBody(30, level);
        phases.tick(zombie, level);
        phases.save(state);
        assertEquals(1, state.getInt("nextPhaseIndex"));
    }

    @Test void preplacedPlantsAndZombiesPublishTheirConfiguredAttributesAtSpawn() {
        AttributeOverrides attributes = AttributeOverrides.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"pvzce:max_health\":50,\"pvzce:render_scale\":0.5}")).getOrThrow();
        LevelDef def = TestLevels.copy(quietLevel().def()).initialEntities(List.of(
                new com.pvzce.api.content.InitialEntityDef("plant", id("pea_shooter"), 2, 0,
                        Optional.empty(), attributes),
                new com.pvzce.api.content.InitialEntityDef("zombie", id("basic_zombie"), 7, 0,
                        Optional.empty(), attributes))).build();
        LevelServer level = new LevelServer(def, 42L);
        List<PvzcePacket> packets = new ArrayList<>();
        level.flushPending(packets::add);
        var spawns = packets.stream().filter(EntitySpawnS2C.class::isInstance).map(EntitySpawnS2C.class::cast).toList();
        assertEquals(2, spawns.size());
        for (EntitySpawnS2C spawn : spawns) {
            assertEquals(50, spawn.health());
            assertEquals(50, spawn.maxHealth());
            assertEquals(.5F, spawn.scale());
        }
    }

    @Test void runtimeChangesReachTheMirrorAndCleanTicksDoNotResendAttributes() {
        LevelServer level = quietLevel();
        List<PvzcePacket> packets = new ArrayList<>();
        ZombieEntity server = level.spawnZombie(id("basic_zombie"), 6F, 0);
        level.flushPending(packets::add);
        ClientEntity client = ClientEntity.from((EntitySpawnS2C) packets.get(0));
        client.apply((EntityAttributesS2C) packets.get(1));
        packets.clear();
        server.attributes().get(EntityAttributes.MAX_HEALTH).setBaseValue(75D);
        server.attributes().get(EntityAttributes.RENDER_SCALE).setBaseValue(.25D);
        level.tick(packets::add);
        for (PvzcePacket packet : packets) {
            if (packet instanceof EntityAttributesS2C attributes) client.apply(attributes);
            if (packet instanceof EntityUpdateS2C update && update.entityId() == server.id()) client.apply(update);
        }
        assertEquals(75, server.health(), "lowering the maximum clamps health without restoring it");
        assertEquals(server.maxHealth(), client.maxHealth());
        assertEquals(server.health(), client.health());
        assertEquals(.25F, client.renderScale());
        assertThrows(UnsupportedOperationException.class, () -> client.attributes().put("example:reach", 1D));
        packets.clear();
        for (int i = 0; i < 3; i++) level.tick(packets::add);
        assertFalse(packets.stream().anyMatch(EntityAttributesS2C.class::isInstance));
        packets.clear();
        level.sendFullState(packets::add);
        assertTrue(packets.stream().filter(EntityAttributesS2C.class::isInstance)
                .map(EntityAttributesS2C.class::cast).anyMatch(packet -> packet.entityId() == server.id()
                        && packet.values().get("pvzce:render_scale") == .25D));
    }

    @Test void queuedAndLegacyMiniaturesResumeWithOrdinaryContentIds() {
        var entry = new WaveDef.Entry(id("basic_zombie"), 3, List.of(0), 1F,
                com.pvzce.common.level.SceneBoard.DEFAULT_SURFACE, miniature("basic_zombie"));
        LevelDef def = fixture(List.of(new WaveDef(WaveDef.WaveType.SMALL, 1, 0, List.of(entry),
                Optional.of(15), Optional.of(0)), new WaveDef(WaveDef.WaveType.FINAL, 100_000, 0,
                List.of(new WaveDef.Entry(id("basic_zombie"), 1)))));
        LevelServer original = new LevelServer(def, 42L);
        original.tick(packet -> { });
        CompoundTag state = original.save();
        assertEquals(1, zombies(original).size());
        for (boolean legacy : List.of(false, true)) {
            if (legacy) {
                for (var tag : state.getList("Entities").values()) {
                    if (tag instanceof CompoundTag entity && "zombie".equals(entity.getString("Kind"))) {
                        entity.putString("id", "pvzce:mini_basic_zombie");
                        entity.entries().remove("Attributes");
                    }
                }
                for (var tag : state.getList("PendingWaveSpawns").values()) {
                    if (!(tag instanceof CompoundTag queue)) continue;
                    ListTag zombies = new ListTag();
                    for (int i = 0; i < queue.getList("Zombies").size(); i++)
                        zombies.add(new StringTag("pvzce:mini_basic_zombie"));
                    queue.put("Zombies", zombies);
                    queue.entries().remove("ZombieAttributes");
                }
            }
            LevelServer restored = new LevelServer(def, 42L);
            restored.restore(state);
            for (int i = 0; i < 35; i++) restored.tick(packet -> { });
            assertEquals(3, zombies(restored).size());
            for (ZombieEntity zombie : zombies(restored)) {
                assertEquals(id("basic_zombie"), zombie.defId());
                assertEquals(100, zombie.maxHealth());
                assertEquals(.5F, zombie.renderScale());
            }
        }
    }
}
