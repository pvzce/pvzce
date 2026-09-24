package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky's clock: {@code pvzce:sun_spawn_interval_min} / {@code _max}.
 *
 * <p>What it replaces was a roll every tick against a chance, which had no memory of the last
 * sun: the average was right and the gaps were geometric, so a level that wrote "about ten
 * seconds" also shipped a minute of drought and two suns inside a second. These cases pin the
 * two things the clock promises instead - every gap is inside the range the level wrote, and
 * the first sun is the prompt one.
 */
class SunDropClockTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    /** 1-4 is a day level with waves; the waves are dropped so only the sky is under test. */
    private static LevelDef skyLevel(Map<Identifier, Integer> overrides) {
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>(source.rules());
        overrides.forEach((id, value) -> rules.put(id, new JsonPrimitive(value)));
        return TestLevels.copy(source).rules(rules).waves(List.of()).build();
    }

    /**
     * Every gap the sky leaves is inside {@code [min, max]}.
     *
     * <p>The old roll could not promise this - it could drop two suns on consecutive ticks and
     * then nothing for half a minute - so this is the case that would have failed before, and
     * it fails loudly rather than statistically: with a 60..120 tick range, twenty gaps that
     * are all inside it are not something an unconstrained roll does by accident.
     */
    @Test
    void everyGapIsInsideTheRangeTheLevelWrote() {
        LevelServer level = new LevelServer(skyLevel(Map.of(
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 60,
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 120,
                PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, 30)));
        Bridge bridge = new Bridge();

        // Counted by entity id, not by "is there a drop on the board": a sun lies where it fell
        // for a while, so a presence test would read the same sun on every tick after it landed.
        List<Integer> dropTicks = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int tick = 1; tick <= 2000; tick++) {
            level.tick(bridge);
            for (var entity : level.entities()) {
                if (entity instanceof ResourceDropEntity && seen.add(entity.id())) {
                    dropTicks.add(tick);
                }
            }
        }

        assertTrue(dropTicks.size() >= 15, "the sky keeps dropping: " + dropTicks.size());
        assertEquals(30, dropTicks.get(0), "the first sun is the prompt one");
        // From the first sun on, every gap is the level's own. The boot delay precedes the
        // first sun and does not constrain the second: a level whose opening sun is prompt
        // still gets its steady rhythm afterwards, which is the difference between "5 seconds
        // to the first sun" and "a sun every 5 seconds".
        for (int i = 2; i < dropTicks.size(); i++) {
            int gap = dropTicks.get(i) - dropTicks.get(i - 1);
            assertTrue(gap >= 60 && gap <= 120,
                    "gap " + i + " is " + gap + " ticks, outside the written 60..120");
        }
    }

    /**
     * A level whose range is zero on both ends gets no sky sun at all.
     *
     * <p>The off switch, and the one the shipped night levels write: it replaced
     * {@code sun_spawn_chance: 0}, and a level that turns the sky off must not be handed a sun
     * by the initial delay, which is why the boot delay is measured against the range.
     */
    @Test
    void aZeroRangeMeansNoSkyAtAll() {
        LevelServer level = new LevelServer(skyLevel(Map.of(
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 0,
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 0)));
        Bridge bridge = new Bridge();

        for (int tick = 0; tick < 3000; tick++) {
            level.tick(bridge);
        }
        assertTrue(level.entities().stream().noneMatch(ResourceDropEntity.class::isInstance),
                "nothing falls from a sky that was switched off");
    }

    /**
     * The countdown survives a save.
     *
     * <p>Without it, continuing a level handed the player an immediate sun - the clock would
     * restart at the boot delay, which is shorter than any steady gap by design.
     */
    @Test
    void theCountdownIsPartOfTheSave() {
        LevelServer level = new LevelServer(skyLevel(Map.of(
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 600,
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 600,
                PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, 600)));
        Bridge bridge = new Bridge();
        for (int tick = 0; tick < 100; tick++) {
            level.tick(bridge);
        }
        CompoundTag saved = level.save();
        assertTrue(saved.contains("SunDropCountdown"), "the clock writes its countdown");

        LevelServer restored = new LevelServer(skyLevel(Map.of(
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 600,
                PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 600,
                PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, 600)));
        restored.restore(saved);
        for (int tick = 0; tick < 499; tick++) {
            restored.tick(bridge);
        }
        assertTrue(restored.entities().stream().noneMatch(ResourceDropEntity.class::isInstance),
                "the 500 ticks left of the gap are still 500 ticks after a resume");
    }
}
