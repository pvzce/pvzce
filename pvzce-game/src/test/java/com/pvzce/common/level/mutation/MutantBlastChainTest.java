package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mutant blast does not set off the next one.
 *
 * <p>The reported symptom was a chain: one zombie died, its blast killed the one beside it, that
 * one's blast killed the next, and a single Gargantuar swing emptied a lane in a second. The
 * ceilings alone could not stop it - a blast is deliberately allowed to kill exactly one ordinary
 * zombie. So a death that <em>came from</em> a blast is marked and does not detonate again, for
 * zombies and for plants alike.
 *
 * <p>These tests put the victim just outside the first blast's footprint and just inside the
 * second's, so "did the chain happen" is read off a witness neither blast can reach directly.
 */
class MutantBlastChainTest {
    private static LevelDef lawn;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier id = MutationLevels.levelIds().get(MutationDifficulty.NORMAL.ordinal());
        LevelDef source = BuiltInRegistries.LEVELS.get(id);
        assertNotNull(source, "the normal-tier mutation level must be registered");
        // The mutation levels are pool boards, and the pool's water rows are not where a test
        // about blasts wants to stand. Everything else - the mutation mechanic above all - is
        // the shipped definition.
        Map<Identifier, List<String>> grass = new LinkedHashMap<>();
        List<String> cells = new ArrayList<>();
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                cells.add(x + "," + y);
            }
        }
        grass.put(PvzceIds.GRASS, cells);
        lawn = TestLevels.copy(source).waves(List.of()).scene(grass).build();
    }

    private static LevelServer level() {
        return new LevelServer(lawn);
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    private static ZombieEntity zombie(LevelServer level, String id, float x, int row) {
        ZombieEntity zombie = level.spawnZombie(Identifier.withDefaultNamespace(id),
                level.team(PvzceIds.ZOMBIE_TEAM), x, row);
        assertNotNull(zombie);
        return zombie;
    }

    private static PlantEntity plant(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def);
        PlantEntity plant = level.spawnPlant(def, level.team(PvzceIds.PLANT_TEAM), x, y);
        assertNotNull(plant);
        return plant;
    }

    /**
     * The zombie killed by a zombie's blast does not explode; the one after it is untouched.
     *
     * <p>Layout: A at cell 3, B at cell 4, a peashooter at cell 5. A's own blast covers 2..4 and
     * kills B, and cannot reach the plant. If B answers with its own blast - which covers 3..5 -
     * the plant loses half its health. It must not.
     */
    @Test
    void aZombieKilledByABlastDoesNotBlastBack() {
        LevelServer level = level();
        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_ZOMBIE_BLAST),
                Mutation.Roll.NONE);
        ZombieEntity first = zombie(level, "basic_zombie", 3.5F, 2);
        ZombieEntity second = zombie(level, "basic_zombie", 4.5F, 2);
        PlantEntity witness = plant(level, "pea_shooter", 5, 2);
        level.flushPending(packet -> { });
        int full = witness.health();

        // Killed by something that is not a blast: its own death is what starts the reaction.
        first.damageBody(first.health(), level);
        level.flushPending(packet -> { });

        assertFalse(second.isAlive(), "the first blast has to reach the zombie next to it");
        assertEquals(full, witness.health(),
                "and that zombie's own death must not become a second blast: the plant at cell 5"
                        + " is only inside the *second* footprint");
    }

    /**
     * The same rule on the plant side: a plant killed by a blast does not explode.
     *
     * <p>Two peashooters, both already damaged below half, sit at cells 3 and 4, with a basic
     * zombie at cell 5. Killing the first one normally detonates it, its blast finishes the
     * second, and that second one's blast would kill the zombie - which is the chain.
     */
    @Test
    void aPlantKilledByABlastDoesNotBlastBack() {
        LevelServer level = level();
        level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_PLANT_BLAST),
                Mutation.Roll.NONE);
        PlantEntity first = plant(level, "pea_shooter", 3, 2);
        PlantEntity second = plant(level, "pea_shooter", 4, 2);
        ZombieEntity witness = zombie(level, "basic_zombie", 5.5F, 2);
        level.flushPending(packet -> { });

        // A single blast's worth of plant damage leaves either one standing, so only the *second*
        // is brought to just under that: the first dies to something else, and its blast is what
        // has to finish the second.
        int lethalFromOneBlast = MutantBlast.plantDamage();
        assertTrue(second.health() > lethalFromOneBlast);
        second.damage(second.health() - lethalFromOneBlast + 10);
        first.damage(first.health());
        level.flushPending(packet -> { });

        // The plants die in the level's removal pass, which is where `onPlantDied` is told; the
        // first one's blast lands on the second inside that same pass.
        tick(level, 3);

        assertTrue(second.isRemoved(), "the first blast has to finish the plant beside it");
        assertTrue(witness.isAlive(),
                "and the second plant's death must not become a second blast: the zombie at cell 5"
                        + " is only inside the *second* footprint");
    }
}
