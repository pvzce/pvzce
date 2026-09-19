package com.pvzce.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sun-shroom: small, cheap and weak at first, then grown.
 *
 * <p>The original's mushroom makes a 15-value sun while small and a 25-value one once it has
 * grown, and the games' suns are not all the same size - the small one is drawn smaller. What
 * this pins is that both the value *and* the size follow the growth, and that the mushroom's
 * art switches with it: a grown sun-shroom that still produced 15, or one that fell back to
 * the small idle for the beat it produced on, would each look almost right.
 */
class SunShroomGrowthTest {
    private static final Identifier SUN_SHROOM = PvzceIds.id("sun_shroom");

    private static LevelDef night;
    private static LevelDef cycle;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        assertNotNull(source, "the shipped 1-4 must load");
        // No waves and no sky sun: every drop on this board came out of the mushroom.
        night = withRules(source, Map.of(PvzceIds.RULE_DAY_LENGTH, 1,
                PvzceIds.RULE_NIGHT_LENGTH, 36_000, PvzceIds.RULE_SUN_SPAWN_CHANCE, 0));
        // A long night: the mushroom needs `after_ticks` of darkness to grow in, and a cycle
        // that turned over underneath it would be testing the clock instead.
        cycle = withRules(source, Map.of(PvzceIds.RULE_DAY_LENGTH, 3_600,
                PvzceIds.RULE_NIGHT_LENGTH, 36_000, PvzceIds.RULE_SUN_SPAWN_CHANCE, 0));
    }

    private static LevelDef withRules(LevelDef source, Map<Identifier, Integer> overrides) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>(source.rules());
        overrides.forEach((id, value) -> rules.put(id, PvzceIds.RULE_SUN_SPAWN_CHANCE.equals(id)
                ? new JsonPrimitive(value.floatValue())
                : new JsonPrimitive(value)));
        return TestLevels.copy(source).rules(rules).waves(List.of()).build();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static PlantEntity sunShroom(LevelServer level, CapturingBridge bridge, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(SUN_SHROOM);
        assertNotNull(def, "the sun-shroom must be registered");
        PlantEntity plant = level.spawnPlant(def, level.team(PvzceIds.PLANT_TEAM), x, y);
        level.flushPending(bridge);
        return plant;
    }

    /** Ticks until the mushroom has made another sun, and answers what it made. */
    private static ResourceDropEntity nextSun(LevelServer level, CapturingBridge bridge, int limit) {
        int before = level.entities().size();
        for (int i = 0; i < limit; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
            if (level.entities().size() > before) {
                for (var entity : level.entities()) {
                    if (entity instanceof ResourceDropEntity drop && drop.amount() > 0) {
                        return drop;
                    }
                }
            }
        }
        return null;
    }

    @Test
    void theSmallMushroomMakesSmallFifteenValueSuns() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity shroom = sunShroom(level, bridge, 1, 2);

        ResourceDropEntity first = nextSun(level, bridge, 400);
        assertNotNull(first, "the first sun arrives within its first-delay window");
        assertEquals(15, first.amount(), "a small sun-shroom's sun is worth 15");
        assertEquals(0.65F, first.renderScale(), 0.0001F,
                "and it is drawn smaller than the 25-value one the sky drops");
        assertEquals(EntityAnimations.PRODUCE, shroom.animation());

        ProducerCapability producer = shroom.capability(ProducerCapability.class);
        assertNotNull(producer);
        assertTrue(producer.growth().isPresent(), "the shipped mushroom declares its growth");
        assertTrue(!producer.isGrown(), "and it starts small");
    }

    /**
     * Growing is audible.
     *
     * <p>A mushroom that doubles in size in silence reads as the sprite swapping rather than
     * as the plant maturing, and the original plays a sound for exactly this.
     */
    @Test
    void growingPlaysASound() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity shroom = sunShroom(level, bridge, 1, 2);
        ProducerCapability producer = shroom.capability(ProducerCapability.class);
        assertNotNull(producer);
        bridge.packets.clear();

        tick(level, bridge, producer.growth().orElseThrow().afterTicks() + 1);
        boolean growSound = bridge.packets.stream()
                .filter(packet -> packet instanceof com.pvzce.common.network.packet.EffectEventS2C)
                .map(packet -> (com.pvzce.common.network.packet.EffectEventS2C) packet)
                .anyMatch(event -> event.sound().contains("plantgrow"));
        assertTrue(growSound, "the growth has to be heard: " + producer.growth().orElseThrow().sound());
    }

    @Test
    void itGrowsOnceAndThenMakesTwentyFiveValueSunsInItsGrownArt() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity shroom = sunShroom(level, bridge, 1, 2);
        ProducerCapability producer = shroom.capability(ProducerCapability.class);
        assertNotNull(producer);
        int after = producer.growth().orElseThrow().afterTicks();

        assertNotNull(nextSun(level, bridge, 400), "the small form produces first");
        tick(level, bridge, after);
        assertTrue(producer.isGrown(), "it has grown by its own timer");

        ResourceDropEntity grown = nextSun(level, bridge, 2_000);
        assertNotNull(grown, "and it keeps producing once grown");
        assertEquals(25, grown.amount(), "a grown sun-shroom's sun is worth 25");
        assertEquals(1.0F, grown.renderScale(), 0.0001F, "and it is the full-size sun");
    }

    @Test
    void theGrownMushroomSleepsInItsGrownArt() {
        LevelServer level = new LevelServer(cycle);
        CapturingBridge bridge = new CapturingBridge();
        // The first tick of the night half of the cycle, where a mushroom is awake again.
        level.setDayTicks(3_600);
        PlantEntity shroom = sunShroom(level, bridge, 1, 2);
        ProducerCapability producer = shroom.capability(ProducerCapability.class);
        assertNotNull(producer);
        tick(level, bridge, producer.growth().orElseThrow().afterTicks() + 90);
        assertTrue(producer.isGrown(), "grown during the night");

        // And on into the next day, where it is asleep again - in the grown sleeping pose.
        level.setDayTicks(3_600L + 36_000L + 60L);
        tick(level, bridge, 2);
        assertTrue(shroom.isAsleep(level), "daylight puts it back to sleep");
        assertEquals("sleep_big", shroom.animation(),
                "the grown mushroom sleeps in its grown art, not the small one");
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }
}
